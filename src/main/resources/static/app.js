const { useEffect, useRef, useState } = React;

function looksLikeMarkdown(text) {
  if (!text) {
    return false;
  }

  return /(^|\n)(#{1,6}\s|\-\s|\*\s|\d+\.\s|>\s|```)/m.test(text) || /\*\*.*\*\*/.test(text);
}

function toMarkdownFallback(text) {
  const safeText = (text || "").trim();
  if (!safeText || looksLikeMarkdown(safeText)) {
    return safeText;
  }

  const sentences = safeText
    .split(/(?<=[.!?])\s+/)
    .map((sentence) => sentence.trim())
    .filter(Boolean);

  if (sentences.length <= 1) {
    return `### Response\n\n${safeText}`;
  }

  return `### Response\n\n${sentences.map((sentence) => `- ${sentence}`).join("\n")}`;
}

function escapeHtml(text) {
  return (text || "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
}

function formatInlineMarkdown(text) {
  return escapeHtml(text)
    .replace(/`([^`]+)`/g, "<code>$1</code>")
    .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
    .replace(/\*([^*]+)\*/g, "<em>$1</em>");
}

function renderMarkdownToHtml(text) {
  const source = toMarkdownFallback(text);
  if (!source) {
    return "";
  }

  const lines = source.split(/\r?\n/);
  const html = [];
  let inList = false;
  let inCodeBlock = false;
  let codeLines = [];
  let paragraphLines = [];

  const flushParagraph = () => {
    if (paragraphLines.length === 0) {
      return;
    }
    html.push(`<p>${formatInlineMarkdown(paragraphLines.join(" "))}</p>`);
    paragraphLines = [];
  };

  const closeList = () => {
    if (inList) {
      html.push("</ul>");
      inList = false;
    }
  };

  for (const rawLine of lines) {
    const line = rawLine.trimEnd();

    if (line.startsWith("```")) {
      flushParagraph();
      closeList();
      if (inCodeBlock) {
        html.push(`<pre><code>${escapeHtml(codeLines.join("\n"))}</code></pre>`);
        codeLines = [];
        inCodeBlock = false;
      } else {
        inCodeBlock = true;
      }
      continue;
    }

    if (inCodeBlock) {
      codeLines.push(rawLine);
      continue;
    }

    if (!line.trim()) {
      flushParagraph();
      closeList();
      continue;
    }

    const headingMatch = line.match(/^(#{1,6})\s+(.*)$/);
    if (headingMatch) {
      flushParagraph();
      closeList();
      const level = headingMatch[1].length;
      html.push(`<h${level}>${formatInlineMarkdown(headingMatch[2])}</h${level}>`);
      continue;
    }

    const listMatch = line.match(/^[-*]\s+(.*)$/);
    if (listMatch) {
      flushParagraph();
      if (!inList) {
        html.push("<ul>");
        inList = true;
      }
      html.push(`<li>${formatInlineMarkdown(listMatch[1])}</li>`);
      continue;
    }

    closeList();
    paragraphLines.push(line.trim());
  }

  flushParagraph();
  closeList();

  if (inCodeBlock) {
    html.push(`<pre><code>${escapeHtml(codeLines.join("\n"))}</code></pre>`);
  }

  return html.join("");
}

function MarkdownBlock({ text, className = "" }) {
  const html = renderMarkdownToHtml(text);

  return <div className={className} dangerouslySetInnerHTML={{ __html: html }}></div>;
}

function App() {
  const [query, setQuery] = useState("");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const [history, setHistory] = useState([]);
  const [pendingQuery, setPendingQuery] = useState("");
  const [historySummary, setHistorySummary] = useState(null);
  const [historyLoading, setHistoryLoading] = useState(true);
  const [showLogs, setShowLogs] = useState(false);
  const [feedbackLoading, setFeedbackLoading] = useState({});
  const [streamMessage, setStreamMessage] = useState("");
  const [streamAnswer, setStreamAnswer] = useState("");
  const [streamMetrics, setStreamMetrics] = useState([]);
  const lastActionRef = useRef("idle");
  const eventSourceRef = useRef(null);
  const bottomRef = useRef(null);

  const scrollToBottom = () => {
    const performScroll = () => {
      bottomRef.current?.scrollIntoView({ behavior: "smooth", block: "end" });
      window.scrollTo({
        top: document.documentElement.scrollHeight,
        behavior: "smooth"
      });
    };

    requestAnimationFrame(() => {
      performScroll();
      requestAnimationFrame(performScroll);
    });
  };

  useEffect(() => {
    loadHistory();
  }, []);

  useEffect(() => {
    if (lastActionRef.current === "response" || lastActionRef.current === "history-load") {
      scrollToBottom();
      lastActionRef.current = "idle";
    }
  }, [history, loading]);

  const loadHistory = async () => {
    setHistoryLoading(true);
    try {
      const response = await fetch("/api/v1/history", {
        headers: {
          Accept: "application/json"
        }
      });

      const payload = await response.json();
      if (!response.ok) {
        throw new Error(payload.message || "Failed to load query history.");
      }

      const items = Array.isArray(payload.items) ? payload.items.slice().reverse() : [];
      setHistory(items);
      setHistorySummary(payload.summary || null);
      lastActionRef.current = "history-load";
    } catch (err) {
      setError((current) => current || err.message || "Unable to load query history.");
    } finally {
      setHistoryLoading(false);
    }
  };

  const handleKeyDown = (event) => {
    if (event.key === "Enter") {
      event.preventDefault();
      submitQuery(event);
    }
  };

  const submitQuery = async (event) => {
    if (event) {
      event.preventDefault();
    }

    const trimmed = query.trim();
    if (!trimmed || loading) {
      if (!trimmed) {
        setError("Enter a question before sending it to Querion.");
      }
      return;
    }

    setLoading(true);
    setError("");
    setPendingQuery(trimmed);
    setStreamMessage("Connecting to the live response stream.");
    setStreamAnswer("");
    setStreamMetrics([]);
    setQuery("");
    scrollToBottom();

    const source = new EventSource(`/api/v1/ask/stream?q=${encodeURIComponent(trimmed)}`);
    eventSourceRef.current = source;

    const parseEventData = (raw) => {
      try {
        return JSON.parse(raw);
      } catch (err) {
        return {};
      }
    };

    source.addEventListener("connected", (streamEvent) => {
      const payload = parseEventData(streamEvent.data);
      setStreamMessage(payload.message || "Connected. Starting your request.");
    });

    source.addEventListener("status", (streamEvent) => {
      const payload = parseEventData(streamEvent.data);
      setStreamMessage(payload.message || "Working on your request.");
    });

    source.addEventListener("chunk", (streamEvent) => {
      const payload = parseEventData(streamEvent.data);
      const chunk = payload.message || "";
      if (!chunk) {
        return;
      }
      setStreamMessage("Streaming answer.");
      setStreamAnswer((current) => `${current}${chunk}`);
    });

    source.addEventListener("metric", (streamEvent) => {
      const payload = parseEventData(streamEvent.data);
      setStreamMetrics((current) => [...current, payload]);
    });

    source.addEventListener("complete", async (streamEvent) => {
      const payload = parseEventData(streamEvent.data);
      setStreamAnswer(payload.answer || "");
      setStreamMessage("");
      source.close();
      eventSourceRef.current = null;
      await loadHistory();
      lastActionRef.current = "response";
      setLoading(false);
      setPendingQuery("");
    });

    source.addEventListener("failure", async (streamEvent) => {
      const payload = parseEventData(streamEvent.data);
      setError(payload.message || "Unable to reach the API.");
      source.close();
      eventSourceRef.current = null;
      setLoading(false);
      setPendingQuery("");
      setStreamMessage("");
      setStreamMetrics([]);
      await loadHistory();
      lastActionRef.current = "response";
    });

    source.onerror = () => {
      if (!eventSourceRef.current) {
        return;
      }

      setError("The live response stream was interrupted.");
      source.close();
      eventSourceRef.current = null;
      setLoading(false);
      setPendingQuery("");
      setStreamMessage("");
      setStreamAnswer("");
      setStreamMetrics([]);
    };
  };

  const stopRequest = () => {
    if (eventSourceRef.current) {
      eventSourceRef.current.close();
      eventSourceRef.current = null;
      setLoading(false);
      setPendingQuery("");
      setStreamMessage("");
      setStreamAnswer("");
      setStreamMetrics([]);
      setError("Request stopped.");
    }
  };

  const sendFeedback = async (requestId, correct) => {
    if (!requestId || feedbackLoading[requestId]) {
      return;
    }

    setFeedbackLoading((current) => ({ ...current, [requestId]: true }));
    try {
      const response = await fetch(
        `/api/v1/feedback?requestId=${encodeURIComponent(requestId)}&correct=${correct}`,
        { method: "POST" }
      );
      const payload = await response.json();
      if (!response.ok) {
        throw new Error(payload.message || "Failed to save feedback.");
      }
      setHistory((current) => current.map((item) => (
        item.requestId === requestId
          ? { ...item, userFeedback: correct, score: payload.score }
          : item
      )));
    } catch (err) {
      setError(err.message || "Unable to save feedback.");
    } finally {
      setFeedbackLoading((current) => ({ ...current, [requestId]: false }));
    }
  };

  const copyAnswer = async (text) => {
    if (!text) {
      return;
    }
    try {
      await navigator.clipboard.writeText(text);
    } catch (err) {
      setError("Unable to copy the answer.");
    }
  };

  const formatResponseTime = (rawDuration) => {
    if (rawDuration == null || Number.isNaN(rawDuration)) {
      return null;
    }

    const normalizedMs = rawDuration > 1_000_000 ? rawDuration / 1_000_000 : rawDuration;

    if (normalizedMs < 1000) {
      return `Response in ${Math.round(normalizedMs)} ms`;
    }

    const seconds = normalizedMs / 1000;
    if (seconds < 10) {
      return `Response in ${seconds.toFixed(1).replace(/\.0$/, "")} sec`;
    }

    return `Response in ${Math.round(seconds)} sec`;
  };

  const formatAverageResponseTime = (rawDuration) => {
    const formatted = formatResponseTime(rawDuration);
    return formatted ? formatted.replace(/^Response in /, "") : "-";
  };

  return (
    <main className="shell">
      <section className="hero">
        <div className="hero-title-wrap">
          <h1>Querion</h1>
        </div>
        <button
          className="logs-toggle"
          type="button"
          onClick={() => setShowLogs((current) => !current)}
        >
          {showLogs ? "Close Logs" : "Metrics"}
        </button>
      </section>

      {showLogs ? (
        <div className="logs-overlay" onClick={() => setShowLogs(false)}>
          <aside className="panel logs-panel" onClick={(event) => event.stopPropagation()}>
            <div className="logs-header">
              <span className="logs-title">Metrics</span>
              <span className="pill pill-compact">
                {historyLoading ? <span className="spinner" aria-hidden="true"></span> : null}
                {historyLoading ? "Syncing" : "Live"}
              </span>
            </div>

            <div className="stats-grid stats-grid-primary">
              <div className="stat-card">
                <span className="stat-label">Data Queries</span>
                <strong className="stat-value">{historySummary?.totalQueries ?? 0}</strong>
              </div>
              <div className="stat-card">
                <span className="stat-label">Success</span>
                <strong className="stat-value">{historySummary?.successCount ?? 0}</strong>
              </div>
              <div className="stat-card">
                <span className="stat-label">Failed</span>
                <strong className="stat-value">{historySummary?.failedCount ?? 0}</strong>
              </div>
              <div className="stat-card">
                <span className="stat-label">Success Rate</span>
                <strong className="stat-value stat-value-small">
                  {historySummary?.successRate != null ? `${historySummary.successRate.toFixed(1)}%` : "-"}
                </strong>
              </div>
            </div>

            <div className="stats-grid stats-grid-secondary">
              <div className="stat-card">
                <span className="stat-label">Latest</span>
                <strong className="stat-value stat-value-small">
                  {historySummary?.latestQueryAt ? new Date(historySummary.latestQueryAt).toLocaleTimeString() : "-"}
                </strong>
              </div>
              <div className="stat-card">
                <span className="stat-label">Avg Response</span>
                <strong className="stat-value stat-value-small">
                  {formatAverageResponseTime(historySummary?.averageResponseTimeMs)}
                </strong>
              </div>
            </div>

          </aside>
        </div>
      ) : null}

      <section className="chat-shell">
        <section className="messages">
          <div className="messages-head">
            <span></span>
            <span className="pill">
              {loading ? <span className="spinner" aria-hidden="true"></span> : null}
              {loading ? "Thinking" : "Ready"}
            </span>
          </div>

          <div className="message-list">
            {error ? (
              <div className="error">{error}</div>
            ) : null}

            {history.length === 0 ? (
              <div className="empty-state">
                Start with a question below. Important query history will appear here and in the logs panel.
              </div>
            ) : history.map((item, index) => (
              <article className="message" key={`${item.requestId || item.createdAt}-${index}`}>
                <div className="bubble user">
                  <div className="bubble-copy">{item.userQuery}</div>
                </div>
                <div className="bubble assistant">
                  <MarkdownBlock
                    className="bubble-copy markdown-body"
                    text={
                      item.executionStatus === "FAILED"
                        ? (item.errorMessage || "Failed to generate response.")
                        : (item.finalAnswer || "No answer returned.")
                    }
                  />
                  {item.queryType === "DATA" && item.keyInsights ? (
                    <div className="insights-card">
                      <div className="bubble-role">Key Insights</div>
                      <MarkdownBlock className="bubble-copy markdown-body" text={item.keyInsights} />
                    </div>
                  ) : null}
                  {item.queryType === "DATA" && item.executionTimeMs != null ? (
                    <div className="log-meta">{formatResponseTime(item.executionTimeMs)}</div>
                  ) : null}
                  <div className="feedback-row">
                    <button
                      className={`feedback-btn ${item.userFeedback === true ? "active" : ""}`}
                      type="button"
                      aria-label="Correct"
                      disabled={feedbackLoading[item.requestId]}
                      onClick={() => sendFeedback(item.requestId, true)}
                    >
                      <svg viewBox="0 0 24 24" aria-hidden="true">
                        <path d="M14 9V5a3 3 0 0 0-3-3l-1 5-3 4v10h9a3 3 0 0 0 3-2.4l1-6A3 3 0 0 0 17 9h-3Z"></path>
                        <path d="M4 11h3v10H4z"></path>
                      </svg>
                    </button>
                    <button
                      className={`feedback-btn ${item.userFeedback === false ? "active negative" : ""}`}
                      type="button"
                      aria-label="Wrong"
                      disabled={feedbackLoading[item.requestId]}
                      onClick={() => sendFeedback(item.requestId, false)}
                    >
                      <svg viewBox="0 0 24 24" aria-hidden="true">
                        <path d="M10 15v4a3 3 0 0 0 3 3l1-5 3-4V3H8a3 3 0 0 0-3 2.4l-1 6A3 3 0 0 0 7 15h3Z"></path>
                        <path d="M17 3h3v10h-3z"></path>
                      </svg>
                    </button>
                    <button
                      className="feedback-btn"
                      type="button"
                      aria-label="Copy answer"
                      onClick={() => copyAnswer(
                        item.executionStatus === "FAILED"
                          ? (item.errorMessage || "Failed to generate response.")
                          : (item.finalAnswer || "No answer returned.")
                      )}
                    >
                      <svg viewBox="0 0 24 24" aria-hidden="true">
                        <path d="M9 9h11v11H9z"></path>
                        <path d="M4 4h11v2H6v9H4z"></path>
                      </svg>
                    </button>
                  </div>
                </div>
              </article>
            ))}

            {loading && pendingQuery ? (
              <article className="message">
                <div className="bubble user">
                  <div className="bubble-copy">{pendingQuery}</div>
                </div>
              </article>
            ) : null}

            {loading ? (
              <article className="message">
                <div className="bubble assistant loading">
                  <span className="spinner" aria-hidden="true"></span>
                  <div className="bubble-copy">
                    {streamAnswer || streamMessage || "Thinking..."}
                    {streamMetrics.length > 0 ? (
                      <div className="log-stream">
                        {streamMetrics.map((item, index) => (
                          <div className="log-meta" key={`${item.stage || "metric"}-${index}`}>
                            {item.message || "LLM metric received."}
                          </div>
                        ))}
                      </div>
                    ) : null}
                  </div>
                </div>
              </article>
            ) : null}
          </div>
          <div ref={bottomRef} aria-hidden="true"></div>
        </section>

        <form className="panel composer" onSubmit={submitQuery}>
          <div className="composer-box">
            <input
              type="text"
              id="queryBox"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              onKeyDown={handleKeyDown}
              placeholder="Ask Querion"
            />
            {loading ? (
              <button className="send" type="button" aria-label="Stop" onClick={stopRequest}>
                <svg viewBox="0 0 24 24" aria-hidden="true">
                  <path d="M7 7h10v10H7z"></path>
                </svg>
              </button>
            ) : (
              <button
                className="send"
                type="submit"
                disabled={!query.trim()}
                aria-label="Send"
              >
                <svg viewBox="0 0 24 24" aria-hidden="true">
                  <path d="M3.4 20.4 21 12 3.4 3.6l.2 6 12.4 2.4-12.4 2.4-.2 6Z"></path>
                </svg>
              </button>
            )}
          </div>

          {/* <div className="composer-note">Querion can make mistakes. Please verify important results.</div> */}
        </form>
      </section>
    </main>
  );
}

ReactDOM.createRoot(document.getElementById("root")).render(<App />);
