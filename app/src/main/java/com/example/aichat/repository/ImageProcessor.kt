package com.example.aichat.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

class ImageProcessor(
    private val context: Context
) {

    /**
     * يحول Uri إلى Bitmap ثم إلى JPEG ثم Base64.
     *
     * @return Base64 للصورة، أو null إذا تعذر قراءة الصورة.
     */
    fun uriToBase64(
        uri: Uri
    ): String? {

        val stream =
            context.contentResolver
                .openInputStream(uri)
                ?: return null

        stream.use {

            val bitmap =
                BitmapFactory.decodeStream(it)
                    ?: return null

            return bitmapToBase64(bitmap)
        }
    }

    /**
     * يحول Bitmap إلى JPEG ثم Base64.
     */
    fun bitmapToBase64(
        bitmap: Bitmap
    ): String {

        val output =
            ByteArrayOutputStream()

        bitmap.compress(
            Bitmap.CompressFormat.JPEG,
            85,
            output
        )

        return Base64.encodeToString(
            output.toByteArray(),
            Base64.NO_WRAP
        )
    }
}
