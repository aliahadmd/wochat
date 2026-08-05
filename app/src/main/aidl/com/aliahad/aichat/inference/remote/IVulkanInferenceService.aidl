package com.aliahad.aichat.inference.remote;

import android.os.Bundle;
import com.aliahad.aichat.inference.remote.IVulkanGenerationCallback;

interface IVulkanInferenceService {
    Bundle loadModel(String path, String displayName, int contextTokens, int declaredContextTokens,
        float temperature, String modelSha256);
    Bundle loadProjector(String path, int imageTokenBudget);
    void unloadProjector();
    Bundle restoreSession(String requestPath);
    oneway void generate(String requestPath, IVulkanGenerationCallback callback);
    int countTokens(String requestPath);
    Bundle verifyLoadedContext();
    oneway void cancel();
    void unload();
    Bundle benchmark(String path, String displayName, int maxNewTokens, int maxAnswerTokens,
        float temperature, boolean thinkingEnabled, String systemPrompt);
    String systemInfo();
}
