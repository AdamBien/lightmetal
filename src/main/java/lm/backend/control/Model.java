package lm.backend.control;

import static lm.backend.ffm.llama_h.llama_h.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import lm.backend.ffm.llama_h.llama_model_params;
import lm.configuration.entity.ContextParams;

public final class Model implements AutoCloseable {

    private static final AtomicBoolean INITIALIZED = new AtomicBoolean();

    private final Arena modelArena = Arena.ofShared();
    final MemorySegment handle;
    final MemorySegment vocab;

    public Model(Path gguf) {
        ensureBackendInit();
        var pathSeg = modelArena.allocateFrom(gguf.toAbsolutePath().toString());
        var params = llama_model_default_params(modelArena);
        llama_model_params.n_gpu_layers(params, -1);
        this.handle = llama_model_load_from_file(pathSeg, params);
        if (MemorySegment.NULL.equals(handle)) {
            modelArena.close();
            throw new IllegalStateException("failed to load model: " + gguf);
        }
        this.vocab = llama_model_get_vocab(handle);
        logChatTemplate();
    }

    public Context newContext(ContextParams cfg) {
        return new Context(this, cfg);
    }

    public int vocabSize() {
        return llama_vocab_n_tokens(vocab);
    }

    public int contextLength() {
        return llama_model_n_ctx_train(handle);
    }

    @Override
    public void close() {
        llama_model_free(handle);
        modelArena.close();
    }

    private void logChatTemplate() {
        var tmplPtr = llama_model_chat_template(handle, MemorySegment.NULL);
        if (MemorySegment.NULL.equals(tmplPtr)) {
            System.err.println("[chat_template] (none stored in GGUF)");
            return;
        }
        var tmpl = tmplPtr.reinterpret(Long.MAX_VALUE).getString(0);
        System.err.println("[chat_template]");
        System.err.println(tmpl);
        System.err.println("[/chat_template]");
    }

    private static void ensureBackendInit() {
        if (INITIALIZED.compareAndSet(false, true)) {
            llama_backend_init();
            Runtime.getRuntime().addShutdownHook(new Thread(Model::shutdown));
        }
    }

    private static void shutdown() {
        llama_backend_free();
    }
}
