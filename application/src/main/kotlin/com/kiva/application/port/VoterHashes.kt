package com.kiva.application.port

/**
 * Turns a guest's address into what a blog vote is kept under (roadmap phase 15, decision 14): a keyed hash, so the
 * address itself is never stored and a copy of the database can't be matched against addresses without the key.
 */
interface VoterHashes {
    /** 64 lowercase hex characters, the same for the same [ip] while the key stays the same. */
    fun hashOf(ip: String): String
}
