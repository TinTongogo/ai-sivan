package com.icusu.sivan.domain.account;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
* 用户账户实体。
*/
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Account {

    private UUID accountId;
    private String username;
    private String email;
    @JsonIgnore
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String passwordHash;
    private String displayName;
    private String preferences;
    private String quota;
    private String shortId;
    private String status;
    @Builder.Default
    private int tokenVersion = 0;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_DISABLED = "disabled";

    public void activate() { this.status = STATUS_ACTIVE; this.updatedAt = LocalDateTime.now(); }
    public void disable() { this.status = STATUS_DISABLED; this.updatedAt = LocalDateTime.now(); }
    public boolean isActive() { return STATUS_ACTIVE.equals(this.status); }
}
