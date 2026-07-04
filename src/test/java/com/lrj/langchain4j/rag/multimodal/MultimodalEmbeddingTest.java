package com.lrj.langchain4j.rag.multimodal;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 原生多模态 embedding 的确定性单测（不连网、不连模型）：
 * <ul>
 *   <li>{@link DefaultMultimodalEmbeddingModel}：覆盖 {@code post()} 桩掉 HTTP 层，验证请求体拼装
 *       （文本 / 图片 data-uri）+ 响应解析成期望维度 float[]。</li>
 *   <li>{@link MultimodalRetrievalService}：图片 → 向量 → 带 {@code type=image}/{@code file_name}
 *       metadata 存进 {@link InMemoryEmbeddingStore}；文本 query 触发 search 并命中该图。</li>
 * </ul>
 */
class MultimodalEmbeddingTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** 桩 HTTP 层：记录最后一次请求体，回一段 4 维 embedding 的 OpenAI 兼容响应。 */
    static class StubHttpModel extends DefaultMultimodalEmbeddingModel {
        final AtomicReference<String> lastBody = new AtomicReference<>();
        final String responseJson;

        StubHttpModel(MultimodalEmbeddingProperties props, ObjectMapper mapper, String responseJson) {
            super(props, mapper);
            this.responseJson = responseJson;
        }

        @Override
        protected String post(String jsonBody) {
            lastBody.set(jsonBody);
            return responseJson;
        }
    }

    private static MultimodalEmbeddingProperties props(int dim) {
        MultimodalEmbeddingProperties p = new MultimodalEmbeddingProperties();
        p.setDimension(dim);
        p.setModelName("jinaai/jina-clip-v2");
        p.setImageInputFormat("data-uri");
        return p;
    }

    @Test
    void embedText_buildsInputAndParsesVector() {
        String resp = "{\"data\":[{\"embedding\":[0.1,0.2,0.3,0.4]}]}";
        StubHttpModel model = new StubHttpModel(props(4), mapper, resp);

        float[] vec = model.embedText("红色的跑车");

        assertEquals(4, vec.length);
        assertEquals(0.3f, vec[2], 1e-6);
        // 文本 query 以字符串元素进 input 数组
        assertTrue(model.lastBody.get().contains("红色的跑车"));
        assertTrue(model.lastBody.get().contains("\"model\":\"jinaai/jina-clip-v2\""));
    }

    @Test
    void embedImage_buildsDataUriInput() {
        String resp = "{\"data\":[{\"embedding\":[1.0,0.0,0.0,0.0]}]}";
        StubHttpModel model = new StubHttpModel(props(4), mapper, resp);

        float[] vec = model.embedImage(new byte[]{1, 2, 3}, "image/png");

        assertEquals(4, vec.length);
        // 图片以 {"image":"data:image/png;base64,..."} 对象元素进 input
        assertTrue(model.lastBody.get().contains("\"image\":\"data:image/png;base64,"),
                "body=" + model.lastBody.get());
    }

    @Test
    void embedImage_rejectsEmpty() {
        StubHttpModel model = new StubHttpModel(props(4), mapper, "{}");
        try {
            model.embedImage(new byte[0], "image/png");
            assertFalse(true, "expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    /** 固定向量的假模型：文本与图片返回同一向量 → 检索时 cosine=1.0 必命中。 */
    static class FakeModel implements MultimodalEmbeddingModel {
        final float[] vector;
        int textCalls = 0;
        int imageCalls = 0;

        FakeModel(float[] vector) { this.vector = vector; }

        @Override public float[] embedText(String text) { textCalls++; return vector; }
        @Override public float[] embedImage(byte[] image, String mimeType) { imageCalls++; return vector; }
        @Override public int dimension() { return vector.length; }
    }

    @Test
    void ingestImage_storesWithImageMetadata_andTextSearchHits() {
        FakeModel fake = new FakeModel(new float[]{0.5f, 0.5f, 0.5f, 0.5f});
        EmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
        MultimodalRetrievalService svc = new MultimodalRetrievalService(fake, store, 5, 0.0);

        String id = svc.ingestImage(new byte[]{9, 8, 7}, "image/jpeg", "car.jpg");

        assertNotNull(id);
        assertEquals(1, fake.imageCalls);

        List<MultimodalRetrievalService.ImageMatch> hits = svc.searchByText("a car", 5, -1);

        assertEquals(1, fake.textCalls, "text query must be embedded (search call issued)");
        assertFalse(hits.isEmpty(), "text->image search should hit the ingested image");
        assertEquals("car.jpg", hits.get(0).fileName());
        assertTrue(hits.get(0).score() > 0.99, "same vector -> cosine ~ 1.0");
    }

    @Test
    void search_filtersOutNonImageEntries() {
        FakeModel fake = new FakeModel(new float[]{0.5f, 0.5f, 0.5f, 0.5f});
        InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
        // 直接塞一条非 image（无 type 元数据）的向量，模拟主 RAG 文本 chunk 共库
        store.add(dev.langchain4j.data.embedding.Embedding.from(new float[]{0.5f, 0.5f, 0.5f, 0.5f}),
                TextSegment.from("just text", new dev.langchain4j.data.document.Metadata()
                        .put("tenantId", "anonymous")));

        MultimodalRetrievalService svc = new MultimodalRetrievalService(fake, store, 5, 0.0);
        svc.ingestImage(new byte[]{1}, "image/png", "pic.png");

        List<MultimodalRetrievalService.ImageMatch> hits = svc.searchByText("q", 5, -1);
        // 只应命中 image 那条，文本 chunk 被 type=image filter 挡掉
        assertEquals(1, hits.size());
        assertEquals("pic.png", hits.get(0).fileName());
    }
}
