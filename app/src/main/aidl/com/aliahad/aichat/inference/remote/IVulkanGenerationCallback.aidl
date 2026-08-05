package com.aliahad.aichat.inference.remote;

oneway interface IVulkanGenerationCallback {
    void onBatch(int phase, String thoughtDelta, String answerDelta);
    void onCompleted(int reason, int answerTokens, int continuationCount);
    void onFailure(int stage, String message);
}
