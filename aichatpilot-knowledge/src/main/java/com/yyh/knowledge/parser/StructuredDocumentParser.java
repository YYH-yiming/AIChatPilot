package com.yyh.knowledge.parser;

import com.yyh.common.exception.BusinessException;
import com.yyh.common.result.ResultCode;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.pdfbox.pdmodel.PDDocument;
import technology.tabula.ObjectExtractor;
import technology.tabula.Page;
import technology.tabula.RectangularTextContainer;
import technology.tabula.Table;
import technology.tabula.extractors.SpreadsheetExtractionAlgorithm;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.xml.sax.ContentHandler;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 结构化文档解析（P1）：按类型走不同抽取，产出有序 {@link ParsedDocument}（标题/段落/表格块），保结构、不拍平。
 * <p>P1a：Markdown 标题树、Excel(POI)、CSV(commons-csv)、PDF 正文(PDFBox sortByPosition 保阅读顺序)、其余 Tika 兜底。
 * <p>P1b 再加 Tabula 抽 PDF 内嵌表格。
 */
@Slf4j
@Component
public class StructuredDocumentParser {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");

    /** PDF 内嵌表格抽取（Tabula）开关；关→仅 PDFBox 正文。P1b 新增，默认开。 */
    @Value("${knowledge.parse.pdf.extract-tables:true}")
    private boolean pdfExtractTables = true;

    public ParsedDocument parse(String filename, byte[] bytes) {
        DocumentType type = DocumentType.fromFilename(filename);
        return switch (type) {
            case MARKDOWN -> parseMarkdown(utf8(bytes));
            case CSV -> parseCsv(bytes);
            case EXCEL -> parseExcel(bytes);
            case PDF -> parsePdf(bytes);
            case PLAIN -> parsePlain(utf8(bytes));
            default -> parseFallbackTika(filename, bytes); // WORD/HTML/PPT/OTHER → Tika 平铺（P1a 兜底）
        };
    }

    // ---- Markdown：按 #/## 标题树切，段落带标题面包屑 ----
    private ParsedDocument parseMarkdown(String text) {
        ParsedDocument doc = new ParsedDocument(DocumentType.MARKDOWN);
        String[] headingByLevel = new String[7]; // 1..6
        StringBuilder para = new StringBuilder();
        for (String raw : text.split("\n", -1)) {
            String line = stripTrailing(raw);
            Matcher m = HEADING.matcher(line);
            if (m.matches()) {
                flushParagraph(doc, para, joinPath(headingByLevel, 6));
                int level = m.group(1).length();
                String title = m.group(2).trim();
                headingByLevel[level] = title;
                for (int i = level + 1; i <= 6; i++) {
                    headingByLevel[i] = null;
                }
                doc.add(DocBlock.heading(level, title, joinPath(headingByLevel, level - 1)));
            } else if (line.isBlank()) {
                flushParagraph(doc, para, joinPath(headingByLevel, 6));
            } else {
                if (para.length() > 0) {
                    para.append('\n');
                }
                para.append(line);
            }
        }
        flushParagraph(doc, para, joinPath(headingByLevel, 6));
        return doc;
    }

    private void flushParagraph(ParsedDocument doc, StringBuilder para, String path) {
        if (para.length() > 0) {
            doc.add(DocBlock.paragraph(para.toString().trim(), path));
            para.setLength(0);
        }
    }

    private String joinPath(String[] headingByLevel, int upToLevel) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= upToLevel; i++) {
            if (headingByLevel[i] != null) {
                if (sb.length() > 0) {
                    sb.append(" > ");
                }
                sb.append(headingByLevel[i]);
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    // ---- CSV：commons-csv 健壮解析（带引号/转义）→ Markdown 表 ----
    private ParsedDocument parseCsv(byte[] bytes) {
        ParsedDocument doc = new ParsedDocument(DocumentType.CSV);
        try (CSVParser parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build()
                .parse(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8))) {
            List<String> headers = parser.getHeaderNames();
            List<List<String>> rows = new ArrayList<>();
            for (CSVRecord rec : parser) {
                List<String> row = new ArrayList<>();
                for (int i = 0; i < headers.size(); i++) {
                    row.add(i < rec.size() ? rec.get(i) : "");
                }
                rows.add(row);
            }
            doc.add(DocBlock.table(toMarkdownTable(headers, rows), null));
        } catch (Exception ex) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "CSV解析失败: " + ex.getMessage());
        }
        return doc;
    }

    // ---- Excel：POI 逐 sheet/行/cell → 每 sheet 一个 Markdown 表（sheet 名作标题） ----
    private ParsedDocument parseExcel(byte[] bytes) {
        ParsedDocument doc = new ParsedDocument(DocumentType.EXCEL);
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            DataFormatter fmt = new DataFormatter();
            for (Sheet sheet : wb) {
                List<List<String>> matrix = new ArrayList<>();
                for (Row row : sheet) {
                    List<String> cells = new ArrayList<>();
                    int last = Math.max(row.getLastCellNum(), 0);
                    boolean nonEmpty = false;
                    for (int c = 0; c < last; c++) {
                        Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                        String val = cell == null ? "" : fmt.formatCellValue(cell);
                        if (!val.isBlank()) {
                            nonEmpty = true;
                        }
                        cells.add(val);
                    }
                    if (nonEmpty) {
                        matrix.add(cells);
                    }
                }
                if (matrix.isEmpty()) {
                    continue;
                }
                doc.add(DocBlock.heading(2, sheet.getSheetName(), null));
                List<String> headers = matrix.get(0);
                List<List<String>> body = matrix.size() > 1 ? matrix.subList(1, matrix.size()) : List.of();
                doc.add(DocBlock.table(toMarkdownTable(headers, body), sheet.getSheetName()));
            }
        } catch (Exception ex) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "Excel解析失败: " + ex.getMessage());
        }
        return doc;
    }

    // ---- PDF：双栏感知正文（中缝检测+分栏抽取，修多栏串行）+ 仅有线框表（Tabula lattice，避免假表） ----
    private ParsedDocument parsePdf(byte[] bytes) {
        ParsedDocument doc = new ParsedDocument(DocumentType.PDF);
        try (PDDocument pdf = PDDocument.load(bytes)) {
            ObjectExtractor extractor = pdfExtractTables ? new ObjectExtractor(pdf) : null;
            SpreadsheetExtractionAlgorithm lattice = new SpreadsheetExtractionAlgorithm();
            int pages = pdf.getNumberOfPages();
            for (int p = 1; p <= pages; p++) {
                addParagraphs(doc, PdfColumnTextExtractor.extractPage(pdf, p - 1)); // 双栏感知，保阅读顺序
                if (extractor != null) {
                    extractLatticeTables(extractor, lattice, p, doc);
                }
            }
        } catch (Exception ex) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "PDF解析失败: " + ex.getMessage());
        }
        return doc;
    }

    /**
     * 仅抽「有线框」表（Tabula lattice）。
     * <p>不用 stream(无线框) 兜底：它会把多栏/对齐正文强行识别成表、搅乱内容（实测简历误报严重）；
     * 宁可漏一个无线框表（其文字仍在正文里、不丢），也不产出假表。无线框真表的结构化留后续按需开。
     * <p>已知局限：正文里仍含被拍平的表格文字（与 lattice 干净表重复），P2 切分时按结构去重/择优。
     */
    private void extractLatticeTables(ObjectExtractor extractor, SpreadsheetExtractionAlgorithm lattice,
                                      int page1based, ParsedDocument doc) {
        try {
            Page page = extractor.extract(page1based);
            if (!lattice.isTabular(page)) {
                return;
            }
            for (Table t : lattice.extract(page)) {
                String md = tabulaTableToMarkdown(t);
                if (md != null) {
                    doc.add(DocBlock.table(md, null));
                }
            }
        } catch (Exception ex) {
            log.warn("PDF 第{}页表格抽取失败，仅保留正文: {}", page1based, ex.getMessage());
        }
    }

    /** Tabula 表 → Markdown（复用 toMarkdownTable）；全空表返回 null。 */
    private String tabulaTableToMarkdown(Table table) {
        List<List<RectangularTextContainer>> rows = table.getRows();
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        List<String> headers = cellsOf(rows.get(0));
        List<List<String>> body = new ArrayList<>();
        for (int r = 1; r < rows.size(); r++) {
            body.add(cellsOf(rows.get(r)));
        }
        boolean any = headers.stream().anyMatch(s -> s != null && !s.isBlank())
                || body.stream().flatMap(List::stream).anyMatch(s -> s != null && !s.isBlank());
        return any ? toMarkdownTable(headers, body) : null;
    }

    @SuppressWarnings("rawtypes")
    private List<String> cellsOf(List<RectangularTextContainer> row) {
        List<String> out = new ArrayList<>();
        for (RectangularTextContainer cell : row) {
            out.add(cell == null ? "" : cell.getText());
        }
        return out;
    }

    // ---- 纯文本 ----
    private ParsedDocument parsePlain(String text) {
        ParsedDocument doc = new ParsedDocument(DocumentType.PLAIN);
        addParagraphs(doc, text);
        return doc;
    }

    // ---- Word/HTML/PPT/其它：Tika 平铺兜底（P1a；结构化 docx/html 后续增强） ----
    private ParsedDocument parseFallbackTika(String filename, byte[] bytes) {
        String text;
        try {
            AutoDetectParser parser = new AutoDetectParser();
            ContentHandler handler = new BodyContentHandler(-1);
            Metadata metadata = new Metadata();
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
            parser.parse(new ByteArrayInputStream(bytes), handler, metadata, new ParseContext());
            text = handler.toString();
        } catch (Exception ex) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "文档解析失败: " + ex.getMessage());
        }
        ParsedDocument doc = new ParsedDocument(DocumentType.fromFilename(filename));
        addParagraphs(doc, text);
        return doc;
    }

    // ---- helpers ----
    private void addParagraphs(ParsedDocument doc, String text) {
        if (text == null) {
            return;
        }
        for (String para : text.split("\\R{2,}")) {
            String p = para.strip();
            if (!p.isEmpty()) {
                doc.add(DocBlock.paragraph(p, null));
            }
        }
    }

    private String toMarkdownTable(List<String> headers, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("| ");
        for (int i = 0; i < headers.size(); i++) {
            if (i > 0) {
                sb.append(" | ");
            }
            sb.append(cleanCell(headers.get(i)));
        }
        sb.append(" |\n|");
        sb.append(" --- |".repeat(Math.max(headers.size(), 1)));
        sb.append('\n');
        for (List<String> row : rows) {
            sb.append("| ");
            for (int i = 0; i < headers.size(); i++) {
                if (i > 0) {
                    sb.append(" | ");
                }
                sb.append(cleanCell(i < row.size() ? row.get(i) : ""));
            }
            sb.append(" |\n");
        }
        return sb.toString().trim();
    }

    /** 单元格内的换行/竖线会破坏 Markdown 表格，转义掉以免错位。 */
    private String cleanCell(String v) {
        if (v == null) {
            return "";
        }
        return v.replace("\r", " ").replace("\n", " ").replace("|", "\\|").trim();
    }

    private String utf8(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private String stripTrailing(String s) {
        return s == null ? "" : s.stripTrailing();
    }
}
