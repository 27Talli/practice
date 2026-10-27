package com.offerpoint.pos.domain.model

data class BackupPreview(val token: String, val backupId: String, val createdAt: Long, val demo: Boolean,
                         val counts: Map<String, Long>, val shops: List<String>)
data class BackupResult(val backupId: String, val bytes: Long, val sha256: String)
