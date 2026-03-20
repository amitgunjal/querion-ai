package com.nanth.querion.engine;

public class Brain {

  private double[] weights;
  private double bias;

  public double predict(double[] features) {
    ensureInitialized(features.length);

    double sum = bias;
    for (int i = 0; i < features.length; i++) {
      sum += weights[i] * features[i];
    }
    return sigmoid(sum);
  }

  public void train(double[][] trainingData, int[] labels, int epochs, double learningRate) {
    if (trainingData == null || trainingData.length == 0 || labels == null || labels.length == 0) {
      return;
    }

    ensureInitialized(trainingData[0].length);

    for (int epoch = 0; epoch < epochs; epoch++) {
      for (int sampleIndex = 0; sampleIndex < trainingData.length; sampleIndex++) {
        double[] features = trainingData[sampleIndex];
        double prediction = predict(features);
        double error = labels[sampleIndex] - prediction;

        for (int featureIndex = 0; featureIndex < features.length; featureIndex++) {
          weights[featureIndex] += learningRate * error * features[featureIndex];
        }
        bias += learningRate * error;
      }
    }
  }

  private void ensureInitialized(int featureCount) {
    if (weights != null) {
      return;
    }

    weights = new double[featureCount];
    for (int i = 0; i < featureCount; i++) {
      weights[i] = 0.0;
    }
    bias = 0.0;
  }

  private double sigmoid(double value) {
    return 1.0 / (1.0 + Math.exp(-value));
  }
}
