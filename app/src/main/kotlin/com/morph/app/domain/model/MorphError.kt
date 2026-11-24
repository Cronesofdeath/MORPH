package com.morph.app.domain.model

import java.io.EOFException
import java.io.IOException
import java.util.zip.ZipException

object MorphError {
    fun explain(error: Exception): String {
        val text=error.message.orEmpty()
        return when {
            error is TargetTooSmall -> text
            error is SecurityException -> "Access to the file has expired. Choose it again in Convert."
            error is java.io.FileNotFoundException -> "The file is unavailable. Check its location and import it again."
            error is org.apache.poi.EncryptedDocumentException || text.contains("password",true) || error is com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException ->
                "This file is locked. Enter the archive password or export an unlocked document."
            error is EOFException || error is ZipException -> "The file is incomplete or damaged. Try a fresh copy."
            error is com.fasterxml.jackson.core.JsonProcessingException -> "The structured data could not be parsed. Check its syntax and encoding."
            error is IllegalArgumentException || error is IllegalStateException -> text.takeIf { it.isNotBlank() && it !in setOf("Failed requirement.","Check failed.") }?.take(300)
                ?: "The file could not pass format validation. Try another output format."
            error is IOException -> "The file could not be read or written. Check free space and try a fresh copy."
            else -> "The conversion engine could not read this file. Try another output format or a fresh copy."
        }
    }
}
