package com.yair.printer

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.print.PrintHelper
import java.io.FileOutputStream

/**
 * Printing is handed to Android's own print framework rather than implemented
 * here, and that is a deliberate choice: the printer accepts QPDL, PCLm and URF
 * but not PDF, so raw bytes sent to port 9100 would come out as garbage. The
 * system print service already renders a document into a language the printer
 * speaks, and it is what makes the phone's share-to-print work at all.
 */
object Printing {

    fun printPdf(context: Context, uri: Uri, jobName: String) {
        val manager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
        manager.print(jobName, PdfFromUri(context, uri, jobName), null)
    }

    fun printImage(context: Context, uri: Uri, jobName: String) {
        PrintHelper(context).apply {
            scaleMode = PrintHelper.SCALE_MODE_FIT
            colorMode = PrintHelper.COLOR_MODE_MONOCHROME
        }.printBitmap(jobName, uri)
    }

    /** Streams an already-made PDF to whatever the user picks in the dialog. */
    private class PdfFromUri(
        private val context: Context,
        private val uri: Uri,
        private val name: String,
    ) : PrintDocumentAdapter() {

        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes?,
            cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback?,
            extras: Bundle?,
        ) {
            if (cancellationSignal?.isCanceled == true) {
                callback?.onLayoutCancelled()
                return
            }

            val info = PrintDocumentInfo.Builder(name)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                // The page count is not read out of the file: the print service
                // discovers it, and a wrong guess here would truncate the job.
                .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                .build()

            callback?.onLayoutFinished(info, true)
        }

        override fun onWrite(
            pages: Array<out PageRange>?,
            destination: ParcelFileDescriptor?,
            cancellationSignal: CancellationSignal?,
            callback: WriteResultCallback?,
        ) {
            val fd = destination?.fileDescriptor
            if (fd == null) {
                callback?.onWriteFailed("אין יעד לכתיבה")
                return
            }

            try {
                context.contentResolver.openInputStream(uri).use { input ->
                    if (input == null) {
                        callback?.onWriteFailed("לא ניתן לקרוא את הקובץ")
                        return
                    }
                    FileOutputStream(fd).use { output -> input.copyTo(output) }
                }
                callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                callback?.onWriteFailed(e.message ?: "הכתיבה נכשלה")
            }
        }
    }
}
