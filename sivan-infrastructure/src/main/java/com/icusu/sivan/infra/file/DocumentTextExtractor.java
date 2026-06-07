package com.icusu.sivan.infra.file;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 文档文本提取器：使用 Apache Tika 从 PDF、DOCX、XLSX 等文档中提取纯文本。
 * 供文件读取工具和富化管道共用。
 */
@Slf4j
@Component
public class DocumentTextExtractor {

    private final Parser parser = new AutoDetectParser();

    /**
     * 从文档字节中提取纯文本内容。
     *
     * @param bytes    文档原始字节
     * @param mimeType 文档 MIME 类型（用于日志）
     * @param maxChars 最大提取字符数，超过此长度截断
     * @return 提取的纯文本，提取失败时返回 null
     */
    public String extractText(byte[] bytes, String mimeType, int maxChars) {
        if (bytes == null || bytes.length == 0) return null;

        BodyContentHandler handler = new BodyContentHandler(maxChars);

        try (InputStream input = new ByteArrayInputStream(bytes)) {
            Metadata metadata = new Metadata();
            if (mimeType != null) {
                metadata.set(Metadata.CONTENT_TYPE, mimeType);
            }
            ParseContext context = new ParseContext();

            parser.parse(input, handler, metadata, context);
        } catch (SAXException e) {
            // BodyContentHandler 达到 maxChars 上限时已写入的内容仍可通过 toString() 获取
            String partialText = handler.toString();
            if (partialText != null && !partialText.isBlank()) {
                log.info("文档内容超过 {} 字符限制，已截断: mimeType={}, extractedChars={}",
                        maxChars, mimeType, partialText.length());
                return partialText;
            }
            log.warn("文档解析 SAX 异常: mimeType={}, {}", mimeType, e.getMessage());
            return null;
        } catch (TikaException e) {
            log.warn("文档解析 Tika 异常: mimeType={}, {}", mimeType, e.getMessage());
            return null;
        } catch (IOException e) {
            log.warn("文档读取 IO 异常: mimeType={}, {}", mimeType, e.getMessage());
            return null;
        }

        String text = handler.toString();
        log.info("文档解析完成: mimeType={}, inputSize={}KB, extractedChars={}",
                mimeType, bytes.length / 1024, text.length());
        return text;
    }
}
