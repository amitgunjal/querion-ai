package com.nanth.querion.controllers;

import com.nanth.querion.engine.ClassifierBrain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class Scheduler {

  private static final Logger log = LoggerFactory.getLogger(Scheduler.class);

  @Autowired
  ClassifierBrain qcService;

//  @Scheduled(cron = "0 */2 * * * *")
//  public void retrain() {
//    log.info("Classifier retraining started");
//    try {
//      qcService.trainFromHistory();
//      log.info("Classifier retraining completed");
//    } catch (Exception e) {
//      log.error("Classifier retraining failed", e);
//    }
//  }
}
