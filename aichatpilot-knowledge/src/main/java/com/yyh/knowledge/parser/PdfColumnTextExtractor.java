package com.yyh.knowledge.parser;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.PDFTextStripperByArea;
import org.apache.pdfbox.text.TextPosition;

import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 双栏感知的 PDF 单页文本抽取：解决多栏 PDF 用 {@code setSortByPosition} 仍按行跨栏交错（串行）的问题。
 * <p>思路：收集本页所有字符的 x 中点做直方图，双栏会呈「左峰—谷—右峰」，谷底即中缝；
 * 找到→左右栏各自 {@link PDFTextStripperByArea} 上下抽取再拼接（左栏全文 + 右栏全文）；
 * 找不到（单栏）→整页正常抽取。全高分栏只切 x、不碰 y，规避 PDFBox 区域坐标上下翻转的坑。
 * <p>用「中点直方图找波谷」而非「空白列」：简历常有贯穿整页宽的姓名/标题行，整宽行会让任何「空白列」判定失效；
 * 直方图里整宽行只给谷底加一点底噪，谷底仍远低于两侧峰，故更稳健。
 * <p>局限：仅处理「整页两栏」常见版式；三栏/分块混排/跨栏表仍可能不准（复杂版式回退 VLM，见规划）。
 */
@Slf4j
final class PdfColumnTextExtractor {

    private PdfColumnTextExtractor() {
    }

    /** 抽取第 {@code pageIndex0}（0 基）页文本，自动按双栏顺序。 */
    static String extractPage(PDDocument doc, int pageIndex0) throws IOException {
        PDPage page = doc.getPage(pageIndex0);
        float pageWidth = page.getMediaBox().getWidth();
        float pageHeight = page.getMediaBox().getHeight();

        List<float[]> spans = collectXSpans(doc, pageIndex0 + 1);
        Float gutter = detectGutter(spans, pageWidth);
        log.debug("PDF 第{}页：{}（页宽 {}）", pageIndex0 + 1,
                gutter == null ? "单栏" : ("双栏 中缝x=" + Math.round(gutter)), Math.round(pageWidth));

        PDFTextStripperByArea area = new PDFTextStripperByArea();
        area.setSortByPosition(true);
        if (gutter == null) {
            area.addRegion("all", new Rectangle2D.Float(0, 0, pageWidth, pageHeight));
            area.extractRegions(page);
            return area.getTextForRegion("all");
        }
        area.addRegion("L", new Rectangle2D.Float(0, 0, gutter, pageHeight));
        area.addRegion("R", new Rectangle2D.Float(gutter, 0, pageWidth - gutter, pageHeight));
        area.extractRegions(page);
        return area.getTextForRegion("L") + "\n\n" + area.getTextForRegion("R");
    }

    /** 收集一页内每个字符的水平区间 [x0, x1]（点为单位，从左起）。 */
    private static List<float[]> collectXSpans(PDDocument doc, int page1based) throws IOException {
        List<float[]> spans = new ArrayList<>();
        PDFTextStripper collector = new PDFTextStripper() {
            @Override
            protected void writeString(String text, List<TextPosition> textPositions) {
                for (TextPosition tp : textPositions) {
                    float x0 = tp.getXDirAdj();
                    spans.add(new float[]{x0, x0 + tp.getWidthDirAdj()});
                }
            }
        };
        collector.setStartPage(page1based);
        collector.setEndPage(page1based);
        collector.getText(doc);
        return spans;
    }

    /**
     * 字符中点直方图找双栏中缝：在中部 [18%,82%] 找计数最低的「谷底」bin，
     * 要求谷底显著低于两侧峰值（≤18%）且两侧都有足量文字（各≥15%），否则判为单栏返回 null。
     */
    private static Float detectGutter(List<float[]> spans, float pageWidth) {
        if (spans.size() < 60 || pageWidth <= 0) {
            return null;
        }
        int bins = 80;
        float binW = pageWidth / bins;
        int[] hist = new int[bins];
        for (float[] s : spans) {
            int b = clamp((int) (((s[0] + s[1]) / 2f) / binW), bins);
            hist[b]++;
        }
        int lo = (int) (bins * 0.18), hi = (int) (bins * 0.82);
        int valleyBin = -1, valleyCount = Integer.MAX_VALUE;
        for (int b = lo; b <= hi; b++) {
            if (hist[b] < valleyCount) {
                valleyCount = hist[b];
                valleyBin = b;
            }
        }
        if (valleyBin < 0) {
            return null;
        }
        int peakL = 0, peakR = 0;
        for (int b = 0; b < valleyBin; b++) {
            peakL = Math.max(peakL, hist[b]);
        }
        for (int b = valleyBin + 1; b < bins; b++) {
            peakR = Math.max(peakR, hist[b]);
        }
        int minPeak = Math.min(peakL, peakR);
        if (minPeak <= 0 || valleyCount > minPeak * 0.18) {
            return null; // 没有明显波谷 → 单栏
        }
        float gutter = (valleyBin + 0.5f) * binW;
        int left = 0, right = 0;
        for (float[] s : spans) {
            float mid = (s[0] + s[1]) / 2f;
            if (mid < gutter) {
                left++;
            } else {
                right++;
            }
        }
        int min = (int) (spans.size() * 0.15);
        if (left < min || right < min) {
            return null; // 一侧文字太少 → 不是真双栏（避免居中单栏被误拆）
        }
        return gutter;
    }

    private static int clamp(int v, int bins) {
        return Math.max(0, Math.min(bins - 1, v));
    }
}
