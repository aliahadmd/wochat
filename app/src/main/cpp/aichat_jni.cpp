#include <android/log.h>
#include <jni.h>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <deque>
#include <fstream>
#include <sstream>
#include <string>
#include <sys/mman.h>
#include <thread>
#include <vector>

#include "chat.h"
#include "common.h"
#include "ggml-backend.h"
#include "llama.h"
#include "sampling.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define LOG_TAG "AIchatNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
llama_model * model = nullptr;
llama_context * context = nullptr;
llama_batch batch{};
// The embedder is a second, much smaller model with its own context and batch.
// It is deliberately NOT the chat model and NOT the chat context: setting
// embeddings mode or clearing memory on the chat context would destroy the KV
// cache that multi-turn prefill reuse depends on.
llama_model * embed_model = nullptr;
llama_context * embed_context = nullptr;
llama_batch embed_batch{};
bool embed_batch_initialized = false;
int embed_dimensions = 0;
std::string embed_file_name;
common_chat_templates_ptr chat_templates;
common_sampler * sampler = nullptr;
mtmd_context * vision_context = nullptr;
std::vector<common_chat_msg> chat_messages;
std::string assistant_text;
std::string utf8_cache;
std::string generated_raw_text;
common_chat_parser_params generation_parser_params;
common_chat_msg parsed_assistant_message;
std::deque<std::pair<int, std::string>> pending_generation_output;
// Gemma vision chunks decode with non-causal attention, so a whole image must fit
// in one ubatch (llama_context::decode asserts n_ubatch >= n_tokens). Batch capacity
// must therefore stay >= the largest image budget used by the active model.
constexpr int max_image_tokens_ceiling = 1120;
constexpr int fast_batch_capacity = 576;
constexpr int best_batch_capacity = 1152;
int batch_capacity = fast_batch_capacity;
int context_size = 4096;
int current_position = 0;
int generated_tokens = 0;
int generated_answer_tokens = 0;
int max_generated_tokens = 512;
int consecutive_unused_tokens = 0;
float sampling_temperature = 0.8f;
bool thinking_enabled = false;
bool batch_initialized = false;
bool generation_parser_active = false;
bool generation_finished = false;
int last_stop_reason = 0;
int last_token_channel = 0;
int model_context_limit = 0;
std::string model_file_name;
std::string projector_file_name;
std::string projector_path;
int projector_max_tokens = 0;

long long elapsed_ms(const std::chrono::steady_clock::time_point & start) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - start
    ).count();
}

std::string from_jstring(JNIEnv * env, jstring value) {
    if (value == nullptr) return {};
    const char * chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars == nullptr ? "" : chars);
    if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    return result;
}

jstring to_jstring(JNIEnv * env, const std::string & value) {
    return env->NewStringUTF(value.c_str());
}

bool valid_utf8(const std::string & value) {
    const auto * bytes = reinterpret_cast<const unsigned char *>(value.c_str());
    while (*bytes != 0) {
        int count = 0;
        if ((*bytes & 0x80) == 0) count = 1;
        else if ((*bytes & 0xE0) == 0xC0) count = 2;
        else if ((*bytes & 0xF0) == 0xE0) count = 3;
        else if ((*bytes & 0xF8) == 0xF0) count = 4;
        else return false;
        bytes++;
        for (int i = 1; i < count; ++i, ++bytes) {
            if ((*bytes & 0xC0) != 0x80) return false;
        }
    }
    return true;
}

void release_file_pages(const std::string & file_name) {
    if (file_name.empty()) return;
    std::ifstream maps("/proc/self/maps");
    std::string line;
    size_t released = 0;
    while (std::getline(maps, line)) {
        if (line.find(file_name) == std::string::npos) continue;
        unsigned long start = 0;
        unsigned long end = 0;
        if (std::sscanf(line.c_str(), "%lx-%lx", &start, &end) != 2 || end <= start) continue;
        if (madvise(reinterpret_cast<void *>(start), end - start, MADV_DONTNEED) == 0) {
            released += end - start;
        }
    }
    if (released > 0) {
        LOGI("Released %zu MiB of file-backed model pages", released / (1024 * 1024));
    }
}

void release_model_file_pages() {
    release_file_pages(model_file_name);
}

void unload_projector() {
    if (vision_context != nullptr) {
        mtmd_free(vision_context);
        vision_context = nullptr;
    }
    projector_file_name.clear();
    projector_path.clear();
    projector_max_tokens = 0;
}

void clear_session() {
    chat_messages.clear();
    assistant_text.clear();
    utf8_cache.clear();
    generated_raw_text.clear();
    parsed_assistant_message = {};
    pending_generation_output.clear();
    current_position = 0;
    generated_tokens = 0;
    generated_answer_tokens = 0;
    consecutive_unused_tokens = 0;
    generation_parser_active = false;
    generation_finished = false;
    last_stop_reason = 0;
    last_token_channel = 0;
    if (context != nullptr) llama_memory_clear(llama_get_memory(context), false);
    if (sampler != nullptr) common_sampler_reset(sampler);
}

void unload_model() {
    clear_session();
    unload_projector();
    if (sampler != nullptr) {
        common_sampler_free(sampler);
        sampler = nullptr;
    }
    chat_templates.reset();
    if (batch_initialized) {
        llama_batch_free(batch);
        batch_initialized = false;
    }
    if (context != nullptr) {
        llama_free(context);
        context = nullptr;
    }
    if (model != nullptr) {
        llama_model_free(model);
        model = nullptr;
    }
    model_file_name.clear();
}

std::string load_projector(const std::string & path, int max_image_tokens) {
    if (model == nullptr) return "Load the language model before its vision projector";
    if (max_image_tokens > batch_capacity) {
        return "Image detail exceeds the loaded model's supported vision budget";
    }
    unload_projector();
    // Projector initialization and model weights otherwise compete for the same
    // HyperOS PSS budget. The model remains mapped and is faulted back for decode.
    release_model_file_pages();
    mtmd_context_params params = mtmd_context_params_default();
    // The Adreno 830 driver loses the device (vk::Queue::submit: ErrorDeviceLost)
    // under the encoder's long shader dispatches — clip must stay on the CPU.
    params.use_gpu = false;
    params.print_timings = true;
    params.n_threads = std::clamp(
        static_cast<int>(std::thread::hardware_concurrency()) - 1,
        4,
        7
    );
    params.warmup = false;
    params.image_min_tokens = 70;
    params.image_max_tokens = std::clamp(max_image_tokens, 70, max_image_tokens_ceiling);
    vision_context = mtmd_init_from_file(path.c_str(), model, params);
    if (vision_context == nullptr) return "Unable to load the Gemma vision projector";
    if (!mtmd_support_vision(vision_context)) {
        unload_projector();
        return "This projector does not support vision input";
    }
    projector_path = path;
    projector_max_tokens = params.image_max_tokens;
    const size_t separator = path.find_last_of('/');
    projector_file_name = separator == std::string::npos ? path : path.substr(separator + 1);
    LOGI("Vision projector ready (CPU, %d threads)", params.n_threads);
    return {};
}

std::vector<ggml_backend_dev_t> devices_for_backend(int backend) {
    std::vector<ggml_backend_dev_t> devices;
    if (backend == 2) {
        auto * gpu = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_GPU);
        if (gpu == nullptr) gpu = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_IGPU);
        if (gpu != nullptr) devices.push_back(gpu);
    } else {
        auto * cpu = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
        if (cpu != nullptr) devices.push_back(cpu);
    }
    devices.push_back(nullptr);
    return devices;
}

std::string load_model(const std::string & path, int backend, int requested_context, float temperature) {
    const auto start = std::chrono::steady_clock::now();
    unload_model();
    auto devices = devices_for_backend(backend);
    if (devices.size() == 1) return backend == 2 ? "Vulkan backend is unavailable" : "CPU backend is unavailable";

    llama_model_params model_params = llama_model_default_params();
    model_params.devices = devices.data();
    model_params.n_gpu_layers = backend == 2 ? -1 : 0;
    model_params.use_mmap = true;
    // CPU repacking duplicates most model weights into anonymous memory. Keeping
    // weights file-backed avoids HyperOS counting that copy against its PSS cap.
    model_params.use_extra_bufts = backend == 2;
    // no_host is still experimental and stalls prompt evaluation on Adreno 830.
    model_params.no_host = false;
    model = llama_model_load_from_file(path.c_str(), model_params);
    if (model == nullptr) return backend == 2 ? "Unable to load model with Vulkan" : "Unable to load model";
    const size_t separator = path.find_last_of('/');
    model_file_name = separator == std::string::npos ? path : path.substr(separator + 1);
    batch_capacity =
        model_file_name.find("E4B") != std::string::npos ||
        model_file_name.find("e4b") != std::string::npos
            ? fast_batch_capacity
            : best_batch_capacity;
    LOGI("Model weights loaded with backend=%d in %lld ms", backend, elapsed_ms(start));

    model_context_limit = std::max(1024, llama_model_n_ctx_train(model));
    context_size = std::clamp(requested_context, 1024, model_context_limit);
    const int threads = std::clamp(
        static_cast<int>(std::thread::hardware_concurrency()) - 2,
        2,
        6
    );
    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = context_size;
    context_params.n_batch = batch_capacity;
    context_params.n_ubatch = batch_capacity;
    context_params.n_threads = threads;
    context_params.n_threads_batch = threads;
    context = llama_init_from_model(model, context_params);
    if (context == nullptr) {
        unload_model();
        return "Unable to allocate the model context";
    }

    batch = llama_batch_init(batch_capacity, 0, 1);
    batch_initialized = true;
    chat_templates = common_chat_templates_init(model, "");
    sampling_temperature = std::clamp(temperature, 0.0f, 2.0f);
    common_params_sampling sampling_params;
    sampling_params.temp = sampling_temperature;
    sampler = common_sampler_init(model, sampling_params);
    if (sampler == nullptr) {
        unload_model();
        return "Unable to initialize sampling";
    }
    clear_session();
    // Build the decode graph during preload, then release clean file-backed pages.
    // The model and context stay loaded while HyperOS gets room for projector work.
    const auto warmup_start = std::chrono::steady_clock::now();
    const auto * vocab = llama_model_get_vocab(model);
    std::vector<llama_token> warmup_tokens;
    if (llama_vocab_bos(vocab) != LLAMA_TOKEN_NULL) warmup_tokens.push_back(llama_vocab_bos(vocab));
    if (llama_vocab_eos(vocab) != LLAMA_TOKEN_NULL) warmup_tokens.push_back(llama_vocab_eos(vocab));
    if (!warmup_tokens.empty()) {
        common_batch_clear(batch);
        for (size_t index = 0; index < warmup_tokens.size(); ++index) {
            common_batch_add(batch, warmup_tokens[index], static_cast<llama_pos>(index), {0}, false);
        }
        llama_decode(context, batch);
        llama_memory_clear(llama_get_memory(context), true);
        LOGI("Warmup decode finished in %lld ms", elapsed_ms(warmup_start));
    }
    release_model_file_pages();
    LOGI(
        "Model and context ready with backend=%d, batch=%d in %lld ms",
        backend,
        batch_capacity,
        elapsed_ms(start)
    );
    return {};
}

int decode_text(const std::string & text, bool add_special, bool parse_special, bool logits_last) {
    auto tokens = common_tokenize(context, text, add_special, parse_special);
    const auto start = std::chrono::steady_clock::now();
    LOGI(
        "Decoding %zu prompt tokens at position %d, logits_last=%d",
        tokens.size(),
        current_position,
        logits_last
    );
    if (current_position + static_cast<int>(tokens.size()) >= context_size - 8) return 1;
    for (size_t offset = 0; offset < tokens.size(); offset += batch_capacity) {
        const int count = std::min<int>(batch_capacity, tokens.size() - offset);
        common_batch_clear(batch);
        for (int index = 0; index < count; ++index) {
            const bool logits = logits_last && offset + index == tokens.size() - 1;
            common_batch_add(batch, tokens[offset + index], current_position + index, {0}, logits);
        }
        if (llama_decode(context, batch) != 0) return 2;
        current_position += count;
    }
    LOGI("Decoded %zu prompt tokens in %lld ms", tokens.size(), elapsed_ms(start));
    return 0;
}

common_chat_templates_inputs chat_inputs(
    const std::vector<common_chat_msg> & messages,
    bool add_generation_prompt
) {
    common_chat_templates_inputs inputs;
    inputs.messages = messages;
    inputs.add_generation_prompt = add_generation_prompt;
    inputs.enable_thinking = thinking_enabled;
    inputs.reasoning_format = COMMON_REASONING_FORMAT_DEEPSEEK;
    inputs.chat_template_kwargs["enable_thinking"] = thinking_enabled ? "true" : "false";
    return inputs;
}

std::string append_message(
    const std::string & role,
    const std::string & content,
    bool add_assistant,
    common_chat_params * output_params = nullptr
) {
    const bool templated = common_chat_templates_was_explicit(chat_templates.get());
    std::string text = content;
    common_chat_msg message{role, content};
    if (templated) {
        std::string past_prompt;
        if (!chat_messages.empty()) {
            past_prompt = common_chat_templates_apply(
                chat_templates.get(),
                chat_inputs(chat_messages, false)
            ).prompt;
        }
        auto next_messages = chat_messages;
        next_messages.push_back(message);
        const auto next = common_chat_templates_apply(
            chat_templates.get(),
            chat_inputs(next_messages, add_assistant)
        );
        text = next.prompt.substr(past_prompt.size());
        if (output_params != nullptr) *output_params = next;
    }
    const bool first = current_position == 0;
    const int result = decode_text(text, first, templated, add_assistant);
    if (result == 1) return "Conversation is too long for the configured context";
    if (result != 0) return "Model failed while processing conversation history";
    chat_messages.push_back(std::move(message));
    return {};
}

std::string format_message(
    const std::string & role,
    const std::string & content,
    bool add_assistant,
    common_chat_params * output_params
) {
    const bool templated = common_chat_templates_was_explicit(chat_templates.get());
    if (!templated) return content;
    std::string past_prompt;
    if (!chat_messages.empty()) {
        past_prompt = common_chat_templates_apply(
            chat_templates.get(),
            chat_inputs(chat_messages, false)
        ).prompt;
    }
    auto next_messages = chat_messages;
    next_messages.push_back({role, content});
    const auto next = common_chat_templates_apply(
        chat_templates.get(),
        chat_inputs(next_messages, add_assistant)
    );
    if (output_params != nullptr) *output_params = next;
    return next.prompt.substr(past_prompt.size());
}

std::string eval_media_message(
    const std::string & role,
    const std::string & prompt,
    const std::vector<std::string> & media_paths,
    bool add_assistant,
    common_chat_params * output_params
) {
    if (vision_context == nullptr) return "Install and load the matching vision projector";
    const auto media_start = std::chrono::steady_clock::now();
    std::string content;
    for (size_t index = 0; index < media_paths.size(); ++index) {
        content += mtmd_default_marker();
        content += "\n";
    }
    content += prompt;
    const std::string formatted = format_message(role, content, add_assistant, output_params);
    std::vector<mtmd_bitmap *> owned_bitmaps;
    std::vector<const mtmd_bitmap *> bitmaps;
    for (const auto & path : media_paths) {
        auto wrapper = mtmd_helper_bitmap_init_from_file(vision_context, path.c_str(), false);
        if (wrapper.bitmap == nullptr) {
            for (auto * bitmap : owned_bitmaps) mtmd_bitmap_free(bitmap);
            release_file_pages(projector_file_name);
            return "Unable to decode an attached image";
        }
        owned_bitmaps.push_back(wrapper.bitmap);
        bitmaps.push_back(wrapper.bitmap);
    }
    mtmd::input_chunks chunks(mtmd_input_chunks_init());
    mtmd_input_text input{
        formatted.c_str(),
        current_position == 0,
        true,
    };
    const int32_t tokenize_result = mtmd_tokenize(
        vision_context,
        chunks.ptr.get(),
        &input,
        bitmaps.data(),
        bitmaps.size()
    );
    for (auto * bitmap : owned_bitmaps) mtmd_bitmap_free(bitmap);
    if (tokenize_result != 0) {
        release_file_pages(projector_file_name);
        return "Unable to prepare multimodal prompt";
    }
    const llama_pos positions = mtmd_helper_get_n_pos(chunks.ptr.get());
    LOGI(
        "Prepared %zu media item(s) with %d positions in %lld ms",
        media_paths.size(),
        positions,
        elapsed_ms(media_start)
    );
    if (current_position + positions >= context_size - 8) {
        release_file_pages(projector_file_name);
        return "Attachments and conversation do not fit the configured context";
    }
    llama_pos new_position = current_position;
    const int32_t eval_result = mtmd_helper_eval_chunks(
        vision_context,
        context,
        chunks.ptr.get(),
        current_position,
        0,
        batch_capacity,
        add_assistant,
        &new_position
    );
    release_file_pages(projector_file_name);
    if (eval_result != 0) return "Model failed while encoding attached media";
    current_position = new_position;
    chat_messages.push_back({role, content});
    LOGI(
        "Encoded %zu media item(s) in %lld ms",
        media_paths.size(),
        elapsed_ms(media_start)
    );
    return {};
}

std::string configure_sampler(const common_chat_params & chat_params) {
    if (sampler != nullptr) {
        common_sampler_free(sampler);
        sampler = nullptr;
    }
    common_params_sampling params;
    params.temp = sampling_temperature;
    params.generation_prompt = chat_params.generation_prompt;
    if (!chat_params.thinking_end_tag.empty()) {
        const auto * vocab = llama_model_get_vocab(model);
        // Leave room in every generation segment for a visible answer. An
        // unlimited reasoning budget can consume the whole segment before
        // Gemma reaches its final channel.
        params.reasoning_budget_tokens = thinking_enabled
            ? std::max(32, std::min(1024, max_generated_tokens / 2))
            : 0;
        if (!chat_params.thinking_start_tag.empty()) {
            params.reasoning_budget_start =
                common_tokenize(vocab, chat_params.thinking_start_tag, false, true);
        }
        params.reasoning_budget_end =
            common_tokenize(vocab, chat_params.thinking_end_tag, false, true);
        params.reasoning_budget_forced =
            common_tokenize(vocab, chat_params.thinking_end_tag, false, true);
    }
    sampler = common_sampler_init(model, params);
    if (sampler == nullptr) return "Unable to initialize sampling";

    generation_parser_params = common_chat_parser_params(chat_params);
    generation_parser_params.reasoning_format = COMMON_REASONING_FORMAT_DEEPSEEK;
    generation_parser_params.parse_tool_calls = false;
    if (!chat_params.parser.empty()) {
        generation_parser_params.parser.load(chat_params.parser);
    }
    generation_parser_active = !chat_params.parser.empty();
    generation_finished = false;
    generated_raw_text.clear();
    parsed_assistant_message = {};
    pending_generation_output.clear();
    return "";
}

void commit_assistant_message() {
    if (!assistant_text.empty()) {
        chat_messages.push_back({"assistant", assistant_text});
        assistant_text.clear();
    }
}

void queue_parsed_generation_output(bool partial) {
    if (!generation_parser_active) return;
    const auto parsed = common_chat_parse(
        generated_raw_text,
        partial,
        generation_parser_params
    );
    const auto diffs = common_chat_msg_diff::compute_diffs(
        parsed_assistant_message,
        parsed
    );
    for (const auto & diff : diffs) {
        if (thinking_enabled && !diff.reasoning_content_delta.empty()) {
            pending_generation_output.emplace_back(1, diff.reasoning_content_delta);
        }
        if (!diff.content_delta.empty()) {
            pending_generation_output.emplace_back(2, diff.content_delta);
            const auto tokens = common_tokenize(
                context,
                diff.content_delta,
                false,
                true
            );
            generated_answer_tokens += std::max<int>(1, tokens.size());
        }
    }
    parsed_assistant_message = parsed;
    assistant_text = parsed.content;
}

jstring pop_generation_output(JNIEnv * env) {
    if (pending_generation_output.empty()) return nullptr;
    auto output = std::move(pending_generation_output.front());
    pending_generation_output.pop_front();
    last_token_channel = output.first;
    return to_jstring(env, output.second);
}
}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_aliahad_aichat_inference_NativeInferenceEngine_nativeInit(
    JNIEnv * env,
    jobject,
    jstring native_lib_dir,
    jint backend
) {
    const std::string path = from_jstring(env, native_lib_dir);
    // Never register the Vulkan plugin in the main process. Some upstream Vulkan
    // fatal paths call exit(1); Vulkan is loaded only by the private :vulkan service.
    ggml_backend_load_best_from_path("cpu", path.c_str());
    if (backend == 2) {
        ggml_backend_load_best_from_path("vulkan", path.c_str());
    }
    llama_backend_init();
    LOGI("%s", llama_print_system_info());
}


constexpr int embed_context_size = 512;

void unload_embedder() {
    if (embed_batch_initialized) {
        llama_batch_free(embed_batch);
        embed_batch_initialized = false;
    }
    if (embed_context != nullptr) {
        llama_free(embed_context);
        embed_context = nullptr;
    }
    if (embed_model != nullptr) {
        llama_model_free(embed_model);
        embed_model = nullptr;
    }
    embed_dimensions = 0;
    embed_file_name.clear();
}

std::string load_embedder(const std::string & path) {
    unload_embedder();
    const auto start = std::chrono::steady_clock::now();
    llama_model_params model_params = llama_model_default_params();
    // CPU only: the Adreno driver is already carrying the chat model, and an
    // embedding pass is short enough that GPU dispatch would not pay for itself.
    model_params.n_gpu_layers = 0;
    model_params.use_mmap = true;
    embed_model = llama_model_load_from_file(path.c_str(), model_params);
    if (embed_model == nullptr) return "Unable to load the embedding model";

    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = embed_context_size;
    context_params.n_batch = embed_context_size;
    context_params.n_ubatch = embed_context_size;
    context_params.embeddings = true;
    // Mean pooling over the sequence: this is an encoder-style use, so there is no
    // "last token" whose hidden state stands for the whole text.
    context_params.pooling_type = LLAMA_POOLING_TYPE_MEAN;
    context_params.n_threads = std::clamp(
        static_cast<int>(std::thread::hardware_concurrency()) - 2, 2, 4
    );
    context_params.n_threads_batch = context_params.n_threads;
    embed_context = llama_init_from_model(embed_model, context_params);
    if (embed_context == nullptr) {
        unload_embedder();
        return "Unable to allocate the embedding context";
    }
    embed_batch = llama_batch_init(embed_context_size, 0, 1);
    embed_batch_initialized = true;
    embed_dimensions = llama_model_n_embd(embed_model);
    if (embed_dimensions <= 0) {
        unload_embedder();
        return "Embedding model reports no embedding dimensions";
    }
    const size_t separator = path.find_last_of('/');
    embed_file_name = separator == std::string::npos ? path : path.substr(separator + 1);
    LOGI(
        "Embedder ready: %d dimensions, %d threads in %lld ms",
        embed_dimensions,
        context_params.n_threads,
        elapsed_ms(start)
    );
    return {};
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_aliahad_aichat_inference_NativeInferenceEngine_nativeLoadEmbedder(
    JNIEnv * env,
    jobject,
    jstring path
) {
    try {
        const std::string error = load_embedder(from_jstring(env, path));
        return error.empty() ? nullptr : to_jstring(env, error);
    } catch (const std::exception & error) {
        return to_jstring(env, error.what());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_aliahad_aichat_inference_NativeInferenceEngine_nativeUnloadEmbedder(JNIEnv *, jobject) {
    unload_embedder();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_aliahad_aichat_inference_NativeInferenceEngine_nativeEmbeddingDimensions(JNIEnv *, jobject) {
    return embed_dimensions;
}

/** Returns an L2-normalized embedding, or null when no embedder is loaded. */
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_aliahad_aichat_inference_NativeInferenceEngine_nativeEmbed(
    JNIEnv * env,
    jobject,
    jstring text
) {
    try {
        if (embed_model == nullptr || embed_context == nullptr) return nullptr;
        auto tokens = common_tokenize(embed_context, from_jstring(env, text), true, true);
        if (tokens.empty()) return nullptr;
        // Long memories are truncated rather than chunked: the embedding stands for
        // the gist, and the lexical half of the ranking still sees the full text.
        if (static_cast<int>(tokens.size()) > embed_context_size) {
            tokens.resize(embed_context_size);
        }
        llama_memory_clear(llama_get_memory(embed_context), true);
        common_batch_clear(embed_batch);
        for (size_t index = 0; index < tokens.size(); ++index) {
            common_batch_add(embed_batch, tokens[index], static_cast<llama_pos>(index), {0}, true);
        }
        if (llama_decode(embed_context, embed_batch) != 0) return nullptr;
        const float * values = llama_get_embeddings_seq(embed_context, 0);
        if (values == nullptr) return nullptr;

        // Normalize here so cosine similarity is a plain dot product on the JVM side.
        double sum = 0.0;
        for (int i = 0; i < embed_dimensions; ++i) sum += static_cast<double>(values[i]) * values[i];
        const float norm = sum > 0.0 ? static_cast<float>(1.0 / std::sqrt(sum)) : 0.0f;
        std::vector<float> normalized(embed_dimensions);
        for (int i = 0; i < embed_dimensions; ++i) normalized[i] = values[i] * norm;

        jfloatArray result = env->NewFloatArray(embed_dimensions);
        if (result == nullptr) return nullptr;
        env->SetFloatArrayRegion(result, 0, embed_dimensions, normalized.data());
        return result;
    } catch (const std::exception & error) {
        LOGE("Embedding failed: %s", error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_aliahad_aichat_inference_NativeInferenceEngine_nativeLoad(
    JNIEnv * env,
    jobject,
    jstring path,
    jint backend,
    jint requested_context,
    jfloat temperature
) {
    try {
        const std::string error = load_model(
            from_jstring(env, path),
            backend,
            requested_context,
            temperature
        );
        return error.empty() ? nullptr : to_jstring(env, error);
    } catch (const std::exception & error) {
        LOGE("Model load failed: %s", error.what());
        unload_model();
        return to_jstring(env, error.what());
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_aliahad_aichat_inference_NativeInferenceEngine_nativeLoadProjector(
    JNIEnv * env,
    jobject,
    jstring path,
    jint max_image_tokens
) {
    try {
        const std::string error = load_projector(from_jstring(env, path), max_image_tokens);
        return error.empty() ? nullptr : to_jstring(env, error);
    } catch (const std::exception & error) {
        LOGE("Projector load failed: %s", error.what());
        unload_projector();
        return to_jstring(env, error.what());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_aliahad_aichat_inference_NativeInferenceEngine_nativeUnloadProjector(JNIEnv *, jobject) {
    unload_projector();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_aliahad_aichat_inference_NativeInferenceEngine_nativeCapabilities(JNIEnv *, jobject) {
    if (vision_context == nullptr) return 0;
    int result = 0;
    if (mtmd_support_vision(vision_context)) result |= 1;
    if (mtmd_support_audio(vision_context)) result |= 2;
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_aliahad_aichat_inference_NativeInferenceEngine_nativeRestore(
    JNIEnv * env,
    jobject,
    jstring system_prompt,
    jobjectArray roles,
    jobjectArray contents,
    jboolean enable_thinking
) {
    try {
        if (model == nullptr || context == nullptr) return to_jstring(env, "No model is loaded");
        clear_session();
        thinking_enabled = enable_thinking;
        std::string error = append_message("system", from_jstring(env, system_prompt), false);
        if (!error.empty()) return to_jstring(env, error);
        const jsize count = env->GetArrayLength(roles);
        if (count != env->GetArrayLength(contents)) return to_jstring(env, "Invalid conversation history");
        for (jsize index = 0; index < count; ++index) {
            auto role_value = static_cast<jstring>(env->GetObjectArrayElement(roles, index));
            auto content_value = static_cast<jstring>(env->GetObjectArrayElement(contents, index));
            error = append_message(
                from_jstring(env, role_value),
                from_jstring(env, content_value),
                false
            );
            env->DeleteLocalRef(role_value);
            env->DeleteLocalRef(content_value);
            if (!error.empty()) return to_jstring(env, error);
        }
        return nullptr;
    } catch (const std::exception & error) {
        LOGE("Session restore failed: %s", error.what());
        return to_jstring(env, error.what());
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_aliahad_aichat_inference_NativeInferenceEngine_nativeBeginUserPrompt(
    JNIEnv * env,
    jobject,
    jstring prompt,
    jstring recorded_prompt,
    jint max_tokens
) {
    try {
        if (model == nullptr || context == nullptr) return to_jstring(env, "No model is loaded");
        assistant_text.clear();
        utf8_cache.clear();
        generated_raw_text.clear();
        parsed_assistant_message = {};
        pending_generation_output.clear();
        generated_tokens = 0;
        generated_answer_tokens = 0;
        consecutive_unused_tokens = 0;
        generation_finished = false;
        last_stop_reason = 0;
        last_token_channel = 0;
        max_generated_tokens = std::clamp<int>(max_tokens, 1, 8192);
        common_chat_params chat_params;
        std::string error = append_message(
            "user",
            from_jstring(env, prompt),
            true,
            &chat_params
        );
        // `prompt` carries this turn's retrieved memories; `recorded_prompt` is the
        // message the user actually sent. Record the latter so the next turn's
        // prefix check compares against durable history and not against scaffolding
        // that only ever applied to this one turn.
        if (error.empty() && recorded_prompt != nullptr && !chat_messages.empty()) {
            chat_messages.back().content = from_jstring(env, recorded_prompt);
        }
        if (error.empty()) error = configure_sampler(chat_params);
        return error.empty() ? nullptr : to_jstring(env, error);
    } catch (const std::exception & error) {
        LOGE("Prompt formatting failed: %s", error.what());
        return to_jstring(env, error.what());
    }
}

std::vector<std::string> from_string_array(JNIEnv * env, jobjectArray values) {
    std::vector<std::string> result;
    if (values == nullptr) return result;
    const jsize count = env->GetArrayLength(values);
    result.reserve(count);
    for (jsize index = 0; index < count; ++index) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(values, index));
        result.push_back(from_jstring(env, value));
        env->DeleteLocalRef(value);
    }
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_aliahad_aichat_inference_NativeInferenceEngine_nativeAppendHistoryMedia(
    JNIEnv * env,
    jobject,
    jstring role,
    jstring prompt,
    jobjectArray paths
) {
    try {
        const std::string error = eval_media_message(
            from_jstring(env, role),
            from_jstring(env, prompt),
            from_string_array(env, paths),
            false,
            nullptr
        );
        return error.empty() ? nullptr : to_jstring(env, error);
    } catch (const std::exception & error) {
        return to_jstring(env, error.what());
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_aliahad_aichat_inference_NativeInferenceEngine_nativeAppendHistoryText(
    JNIEnv * env,
    jobject,
    jstring role,
    jstring content
) {
    try {
        const std::string error = append_message(
            from_jstring(env, role),
            from_jstring(env, content),
            false
        );
        return error.empty() ? nullptr : to_jstring(env, error);
    } catch (const std::exception & error) {
        return to_jstring(env, error.what());
    }
}

// Reports how many leading history messages the live session already holds, so a
// follow-up turn can append its delta instead of re-prefilling from position 0.
//
// Returns -1 when the cache cannot be reused and the caller must rebuild: no
// session, a different system prompt or thinking mode, a message that differs
// from what was decoded, or a session holding MORE history than requested (an
// edited or deleted turn). The session itself is the authority here — the JVM
// keeps no mirror that could drift out of sync with the KV cache.
extern "C" JNIEXPORT jint JNICALL
Java_com_aliahad_aichat_inference_NativeInferenceEngine_nativeSessionPrefixLength(
    JNIEnv * env,
    jobject,
    jstring system_prompt,
    jboolean enable_thinking,
    jobjectArray roles,
    jobjectArray contents
) {
    try {
        if (model == nullptr || context == nullptr) return -1;
        if (chat_messages.empty() || current_position == 0) {
            LOGI("Session reuse declined: no live session");
            return -1;
        }
        if (static_cast<bool>(enable_thinking) != thinking_enabled) {
            LOGI("Session reuse declined: thinking mode changed");
            return -1;
        }
        if (chat_messages.front().role != "system") return -1;
        if (chat_messages.front().content != from_jstring(env, system_prompt)) {
            LOGI(
                "Session reuse declined: system prompt changed (held %zu chars, requested %zu)",
                chat_messages.front().content.size(),
                from_jstring(env, system_prompt).size()
            );
            return -1;
        }

        const jsize requested = env->GetArrayLength(roles);
        if (requested != env->GetArrayLength(contents)) return -1;
        const size_t held = chat_messages.size() - 1;
        if (held > static_cast<size_t>(requested)) {
            LOGI("Session reuse declined: session holds %zu history messages, %d requested",
                 held, requested);
            return -1;
        }

        for (size_t index = 0; index < held; ++index) {
            auto role_value = static_cast<jstring>(env->GetObjectArrayElement(roles, index));
            auto content_value = static_cast<jstring>(env->GetObjectArrayElement(contents, index));
            const bool same =
                chat_messages[index + 1].role == from_jstring(env, role_value) &&
                chat_messages[index + 1].content == from_jstring(env, content_value);
            env->DeleteLocalRef(role_value);
            env->DeleteLocalRef(content_value);
            if (!same) {
                // Never log the contents themselves — this is conversation text.
                LOGI("Session reuse declined: history message %zu differs", index);
                return -1;
            }
        }
        LOGI("Session reuse accepted: %zu of %d history messages already decoded", held, requested);
        return static_cast<jint>(held);
    } catch (const std::exception &) {
        return -1;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_aliahad_aichat_inference_NativeInferenceEngine_nativeBeginUserTurn(
    JNIEnv * env,
    jobject,
    jstring prompt,
    jobjectArray paths,
    jint max_tokens
) {
    try {
        if (model == nullptr || context == nullptr) return to_jstring(env, "No model is loaded");
        assistant_text.clear();
        utf8_cache.clear();
        generated_raw_text.clear();
        parsed_assistant_message = {};
        pending_generation_output.clear();
        generated_tokens = 0;
        generated_answer_tokens = 0;
        consecutive_unused_tokens = 0;
        generation_finished = false;
        last_stop_reason = 0;
        last_token_channel = 0;
        max_generated_tokens = std::clamp<int>(max_tokens, 1, 8192);
        common_chat_params chat_params;
        std::string error = eval_media_message(
            "user",
            from_jstring(env, prompt),
            from_string_array(env, paths),
            true,
            &chat_params
        );
        if (error.empty()) error = configure_sampler(chat_params);
        return error.empty() ? nullptr : to_jstring(env, error);
    } catch (const std::exception & error) {
        LOGE("Multimodal prompt failed: %s", error.what());
        return to_jstring(env, error.what());
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_aliahad_aichat_inference_NativeInferenceEngine_nativeCountTokens(
    JNIEnv * env,
    jobject,
    jstring text
) {
    if (context == nullptr) return -1;
    return static_cast<jint>(common_tokenize(context, from_jstring(env, text), false, true).size());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_aliahad_aichat_inference_NativeInferenceEngine_nativeCurrentContextSize(
    JNIEnv *,
    jobject
) {
    return context == nullptr ? 0 : context_size;
}

extern "C" JNIEXPORT void JNICALL
Java_com_aliahad_aichat_inference_NativeInferenceEngine_nativeFinishGeneration(JNIEnv *, jobject) {
    commit_assistant_message();
}

extern "C" JNIEXPORT void JNICALL
Java_com_aliahad_aichat_inference_NativeInferenceEngine_nativeContinueGeneration(
    JNIEnv *,
    jobject,
    jint max_tokens
) {
    generated_tokens = 0;
    max_generated_tokens = std::clamp<int>(max_tokens, 1, 8192);
    last_stop_reason = 0;
    last_token_channel = 0;
    generation_finished = false;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_aliahad_aichat_inference_NativeInferenceEngine_nativeNextToken(
    JNIEnv * env,
    jobject
) {
    try {
        const auto start = std::chrono::steady_clock::now();
        last_token_channel = 0;
        if (!pending_generation_output.empty()) {
            return pop_generation_output(env);
        }
        if (generation_finished) return nullptr;
        if (generated_tokens >= max_generated_tokens) {
            last_stop_reason = 2;
            return nullptr;
        }
        if (current_position >= context_size - 8) {
            last_stop_reason = 3;
            return nullptr;
        }
        const llama_token token = common_sampler_sample(sampler, context, -1);
        common_sampler_accept(sampler, token, true);
        if (llama_vocab_is_eog(llama_model_get_vocab(model), token)) {
            last_stop_reason = 1;
            generation_finished = true;
            queue_parsed_generation_output(false);
            commit_assistant_message();
            return pop_generation_output(env);
        }
        common_batch_clear(batch);
        common_batch_add(batch, token, current_position, {0}, true);
        if (llama_decode(context, batch) != 0) {
            last_stop_reason = 4;
            return nullptr;
        }
        current_position++;
        generated_tokens++;
        if (generated_tokens == 1 || generated_tokens % 32 == 0) {
            LOGI(
                "Generated token %d at position %d in %lld ms",
                generated_tokens,
                current_position,
                elapsed_ms(start)
            );
        }
        const std::string piece = common_token_to_piece(context, token, true);
        if (piece.rfind("<unused", 0) == 0) {
            consecutive_unused_tokens++;
            if (consecutive_unused_tokens >= 8) {
                env->ThrowNew(
                    env->FindClass("java/lang/IllegalStateException"),
                    "Gemma 4 produced invalid control tokens in llama.cpp Vulkan "
                    "(upstream issue #21516). Import another compatible GGUF."
                );
                return nullptr;
            }
        } else {
            consecutive_unused_tokens = 0;
        }
        utf8_cache += piece;
        if (!valid_utf8(utf8_cache)) return to_jstring(env, "");
        const std::string result = utf8_cache;
        utf8_cache.clear();
        if (generation_parser_active) {
            generated_raw_text += result;
            queue_parsed_generation_output(true);
            if (!pending_generation_output.empty()) {
                return pop_generation_output(env);
            }
            return to_jstring(env, "");
        }
        // Suppress genuine control/special tokens (e.g. Gemma turn tags) without
        // dropping legitimate answer tokens that merely look like "<...>" markup.
        const llama_token_attr token_attrs =
            llama_vocab_get_attr(llama_model_get_vocab(model), token);
        if (token_attrs & (LLAMA_TOKEN_ATTR_CONTROL | LLAMA_TOKEN_ATTR_UNKNOWN)) {
            return to_jstring(env, "");
        }
        last_token_channel = 2;
        generated_answer_tokens++;
        assistant_text += result;
        return to_jstring(env, result);
    } catch (const std::exception & error) {
        LOGE("Token generation failed: %s", error.what());
        last_stop_reason = 5;
        return nullptr;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_aliahad_aichat_inference_NativeInferenceEngine_nativeLastStopReason(JNIEnv *, jobject) {
    return last_stop_reason;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_aliahad_aichat_inference_NativeInferenceEngine_nativeLastTokenChannel(JNIEnv *, jobject) {
    return last_token_channel;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_aliahad_aichat_inference_NativeInferenceEngine_nativeGeneratedAnswerTokens(JNIEnv *, jobject) {
    return generated_answer_tokens;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_aliahad_aichat_inference_NativeInferenceEngine_nativeModelContextLimit(JNIEnv *, jobject) {
    return model_context_limit;
}

extern "C" JNIEXPORT void JNICALL
Java_com_aliahad_aichat_inference_NativeInferenceEngine_nativeUnload(JNIEnv *, jobject) {
    unload_model();
}

extern "C" JNIEXPORT void JNICALL
Java_com_aliahad_aichat_inference_NativeInferenceEngine_nativeReleaseModelPages(JNIEnv *, jobject) {
    release_model_file_pages();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_aliahad_aichat_inference_NativeInferenceEngine_nativeSystemInfo(JNIEnv * env, jobject) {
    return to_jstring(env, llama_print_system_info());
}

extern "C" JNIEXPORT void JNICALL
Java_com_aliahad_aichat_inference_NativeInferenceEngine_nativeShutdown(JNIEnv *, jobject) {
    unload_model();
    unload_embedder();
    llama_backend_free();
}
