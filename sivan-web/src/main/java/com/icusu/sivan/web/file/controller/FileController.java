package com.icusu.sivan.web.file.controller;

import com.icusu.sivan.common.dto.BaseResponse;
import com.icusu.sivan.domain.file.FileStoragePort;
import com.icusu.sivan.web.file.dto.FileUploadResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import com.icusu.sivan.web.shared.security.CurrentAccountId;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 文件上传与获取控制器。
 */
@Slf4j
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final FileStoragePort fileStorageService;

    /**
     * 上传单张图片。
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<BaseResponse<FileUploadResponse>> upload(@RequestPart("file") Mono<FilePart> filePartMono, @CurrentAccountId UUID accountId) {
        return filePartMono.flatMap(filePart ->
                org.springframework.core.io.buffer.DataBufferUtils.join(filePart.content())
                        .map(dataBuffer -> {
                            byte[] bytes = new byte[dataBuffer.readableByteCount()];
                            dataBuffer.read(bytes);
                            org.springframework.core.io.buffer.DataBufferUtils.release(dataBuffer);
                            return bytes;
                        })
                        .map(bytes -> fileStorageService.store(bytes, filePart.filename(), null, accountId))
                        .map(result -> BaseResponse.created(
                                new FileUploadResponse(result.getFileId(), result.getFileName(), result.getMimeType(), result.getFileSize())))
        );
    }

    /**
     * 获取文件（浏览器加载图片）。
     * Cache-Control: 365 天不可变缓存。
     */
    @GetMapping("/{fileId}")
    public Mono<ResponseEntity<Resource>> getFile(@PathVariable UUID fileId, @CurrentAccountId UUID accountId) {
        return Mono.fromCallable(() -> {
            byte[] bytes = fileStorageService.loadBytes(accountId, fileId);
            ByteArrayResource resource = new ByteArrayResource(bytes);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(fileStorageService.getMimeType(accountId, fileId)))
                    .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePrivate().immutable())
                    .body((Resource) resource);
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
