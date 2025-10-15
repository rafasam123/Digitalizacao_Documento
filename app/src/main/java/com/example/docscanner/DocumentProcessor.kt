package com.example.docscanner

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.pow
import kotlin.math.sqrt

class DocumentProcessor {

    init {
        if (!OpenCVLoader.initDebug()) {
            Log.e("OpenCV", "Não foi possível carregar a biblioteca OpenCV!")
        } else {
            Log.d("OpenCV", "Biblioteca OpenCV carregada com sucesso!")
        }
    }

    // FUNÇÃO 1: APENAS ENCONTRA OS CANTOS
    fun findDocumentCorners(bitmap: Bitmap): Array<Point>? {
        val inputMat = Mat()
        Utils.bitmapToMat(bitmap, inputMat)
        val resizedMat = Mat()
        val resizedSize = Size(640.0, 480.0)
        Imgproc.resize(inputMat, resizedMat, resizedSize)
        val preprocessedMat = preprocessImage(resizedMat)
        val documentContour = findLargestValidContour(preprocessedMat)

        if (documentContour != null) {
            val ratioX = inputMat.cols().toDouble() / resizedSize.width
            val ratioY = inputMat.rows().toDouble() / resizedSize.height
            val correctedPoints = documentContour.toArray().map {
                Point(it.x * ratioX, it.y * ratioY)
            }.toTypedArray()
            return orderPoints(correctedPoints) // Retorna os pontos ordenados
        }
        return null
    }

    // FUNÇÃO 2: APLICA O RECORTE E FILTROS
    fun warpAndFilter(bitmap: Bitmap, corners: Array<Point>): Bitmap {
        val inputMat = Mat()
        Utils.bitmapToMat(bitmap, inputMat)
        val warpedMat = warpPerspective(inputMat, MatOfPoint(*corners))
        val finalMat = applyFilters(warpedMat)
        val finalBitmap = Bitmap.createBitmap(finalMat.cols(), finalMat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(finalMat, finalBitmap)
        return finalBitmap
    }

    // Funções auxiliares (iguais às da versão anterior)
    private fun preprocessImage(imageMat: Mat): Mat {
        val grayMat = Mat()
        Imgproc.cvtColor(imageMat, grayMat, Imgproc.COLOR_BGR2GRAY)
        val blurredMat = Mat()
        Imgproc.GaussianBlur(grayMat, blurredMat, Size(5.0, 5.0), 0.0)
        val threshMat = Mat()
        Imgproc.adaptiveThreshold(blurredMat, threshMat, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, 21, 5.0)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        val closedMat = Mat()
        Imgproc.morphologyEx(threshMat, closedMat, Imgproc.MORPH_CLOSE, kernel)
        return closedMat
    }

    private fun findLargestValidContour(imageMat: Mat): MatOfPoint? {
        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(imageMat, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        var largestContour: MatOfPoint? = null
        var maxArea = 0.0
        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area > 2000) {
                val peri = Imgproc.arcLength(MatOfPoint2f(*contour.toArray()), true)
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(MatOfPoint2f(*contour.toArray()), approx, 0.04 * peri, true)
                if (approx.rows() == 4 && area > maxArea) {
                    largestContour = MatOfPoint(*approx.toArray())
                    maxArea = area
                }
            }
        }
        return largestContour
    }

    private fun warpPerspective(imageMat: Mat, contour: MatOfPoint): Mat {
        val orderedPoints = orderPoints(contour.toArray())
        val sourcePoints = MatOfPoint2f(*orderedPoints)
        val (tl, tr, br, bl) = orderedPoints
        val widthA = sqrt((br.x - bl.x).pow(2) + (br.y - bl.y).pow(2))
        val widthB = sqrt((tr.x - tl.x).pow(2) + (tr.y - tl.y).pow(2))
        val maxWidth = maxOf(widthA, widthB)
        val heightA = sqrt((tr.x - br.x).pow(2) + (tr.y - br.y).pow(2))
        val heightB = sqrt((tl.x - bl.x).pow(2) + (tl.y - bl.y).pow(2))
        val maxHeight = maxOf(heightA, heightB)
        val destinationPoints = MatOfPoint2f(Point(0.0, 0.0), Point(maxWidth - 1, 0.0), Point(maxWidth - 1, maxHeight - 1), Point(0.0, maxHeight - 1))
        val perspectiveTransform = Imgproc.getPerspectiveTransform(sourcePoints, destinationPoints)
        val warpedMat = Mat()
        Imgproc.warpPerspective(imageMat, warpedMat, perspectiveTransform, Size(maxWidth, maxHeight))
        return warpedMat
    }

    private fun orderPoints(points: Array<Point>): Array<Point> {
        val sortedByX = points.sortedBy { it.x }
        val leftMost = sortedByX.take(2)
        val rightMost = sortedByX.takeLast(2)
        val (tl, bl) = leftMost.sortedBy { it.y }
        val (tr, br) = rightMost.sortedBy { it.y }
        return arrayOf(tl, tr, br, bl)
    }

    private fun applyFilters(imageMat: Mat): Mat {
        if (imageMat.empty()) return imageMat
        val grayMat = Mat()
        Imgproc.cvtColor(imageMat, grayMat, Imgproc.COLOR_BGR2GRAY, 1)
        val sharpenedMat = Mat()
        Imgproc.GaussianBlur(grayMat, sharpenedMat, Size(0.0, 0.0), 3.0)
        Core.addWeighted(grayMat, 1.5, sharpenedMat, -0.5, 0.0, sharpenedMat)
        val finalMat = Mat()
        Imgproc.adaptiveThreshold(sharpenedMat, finalMat, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, 21, 15.0)
        return finalMat
    }
}