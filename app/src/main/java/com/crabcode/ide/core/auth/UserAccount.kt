package com.crabcode.ide.core.auth

import kotlinx.serialization.Serializable

/** Verified Google account. `sub` is the stable unique user id. */
@Serializable
data class UserAccount(
    val sub: String,
    val email: String,
    val name: String,
    val picture: String? = null
)
