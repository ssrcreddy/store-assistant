package com.example.commerceai.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeChunkerTest {

    @Test
    void oneChunkPerSectionWithStableIds() {
        var chunks = KnowledgeChunker.chunk("returns", """
                # Returns and exchanges

                ## Return window
                Most items can be returned within 30 days.

                ## Items that cannot be returned
                Final sale items.
                """);

        assertThat(chunks).extracting(KnowledgeChunker.Chunk::id)
                .containsExactly("returns#return-window", "returns#items-that-cannot-be-returned");
        assertThat(chunks.get(0).title()).isEqualTo("Return window");
        assertThat(chunks.get(0).content()).isEqualTo("Most items can be returned within 30 days.");
    }

    @Test
    void longSectionsSplitAtParagraphsIntoNumberedParts() {
        String paragraph = "word ".repeat(150).trim(); // ~750 chars
        var chunks = KnowledgeChunker.chunk("faq", "## Long\n" + paragraph + "\n\n" + paragraph + "\n\n" + paragraph);

        assertThat(chunks).extracting(KnowledgeChunker.Chunk::id).containsExactly("faq#long", "faq#long-2", "faq#long-3");
        assertThat(chunks).allMatch(c -> c.content().length() <= KnowledgeChunker.MAX_CHARS);
    }

    @Test
    void bundledKnowledgeBaseLoadsWithUniqueIds() {
        List<KnowledgeChunker.Chunk> chunks = KnowledgeChunker.loadClasspath();

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(25);
        assertThat(chunks).extracting(KnowledgeChunker.Chunk::id).doesNotHaveDuplicates()
                .contains("returns#return-window", "refunds#refund-timing", "payments#card-security");
    }

    @Test
    void keywordSearchFindsTheReturnWindow() {
        var hits = new KeywordKnowledgeBase().search("What is the return window for electronics?", 3);

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).id()).isEqualTo("returns#return-window");
    }
}
