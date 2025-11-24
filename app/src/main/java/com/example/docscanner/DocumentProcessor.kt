package com.example.docscanner

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.pow
import kotlin.math.sqrt

enum class FilterType { NONE, BLACK_AND_WHITE, SHARPEN }

class DocumentProcessor {
    init { if (!OpenCVLoader.initDebug()) Log.e("OpenCV", "Erro ao carregar!") }

    fun findDocumentCorners(bitmap: Bitmap): Array<Point>? {
        val inputMat = Mat()
        Utils.bitmapToMat(bitmap, inputMat)
        val resizedMat = Mat()
        val resizedSize = Size(640.0, 480.0)
        Imgproc.resize(inputMat, resizedMat, resizedSize)
        
        val preprocessed = preprocessImage(resizedMat)
        val contour = findLargestValidContour(preprocessed)

        if (contour != null) {
            val ratioX = inputMat.cols().toDouble() / resizedSize.width
            val ratioY = inputMat.rows().toDouble() / resizedSize.height
            val points = contour.toArray().map { Point(it.x * ratioX, it.y * ratioY) }.toTypedArray()
            return orderPoints(points)
        }
        return null
    }

    fun warpAndFilter(bitmap: Bitmap, corners: Array<Point>, filterType: FilterType): Bitmap {
        val inputMat = Mat()
        Utils.bitmapToMat(bitmap, inputMat)
        val warpedMat = warpPerspective(inputMat, MatOfPoint(*corners))
        val finalMat = applyFilterLogic(warpedMat, filterType)
        val finalBitmap = Bitmap.createBitmap(finalMat.cols(), finalMat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(finalMat, finalBitmap)
        return finalBitmap
    }

    private fun applyFilterLogic(imageMat: Mat, type: FilterType): Mat {
        if (imageMat.empty()) return imageMat
        val resultMat = Mat()
        when (type) {
            FilterType.NONE -> imageMat.copyTo(resultMat)
            FilterType.BLACK_AND_WHITE -> {
                val gray = Mat()
                Imgproc.cvtColor(imageMat, gray, Imgproc.COLOR_BGR2GRAY)
                val blurred = Mat()
                Imgproc.GaussianBlur(gray, blurred, Size(0.0, 0.0), 3.0)
                Core.addWeighted(gray, 1.5, blurred, -0.5, 0.0, gray)
                Imgproc.adaptiveThreshold(gray, resultMat, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, 21, 15.0)
            }
            FilterType.SHARPEN -> {
                val blurred = Mat()
                Imgproc.GaussianBlur(imageMat, blurred, Size(0.0, 0.0), 3.0)
                Core.addWeighted(imageMat, 1.5, blurred, -0.5, 0.0, resultMat)
            }
        }
        return resultMat
    }

    private fun preprocessImage(imageMat: Mat): Mat {
        val gray = Mat(); Imgproc.cvtColor(imageMat, gray, Imgproc.COLOR_BGR2GRAY)
        val blur = Mat(); Imgproc.GaussianBlur(gray, blur, Size(5.0, 5.0), 0.0)
        // Binarização Adaptativa (Melhor que Canny puro)
        val thresh = Mat(); Imgproc.adaptiveThreshold(blur, thresh, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, 11, 5.0)
        val closed = Mat(); Imgproc.morphologyEx(thresh, closed, Imgproc.MORPH_CLOSE, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0)))
        return closed
    }

    private fun findLargestValidContour(imageMat: Mat): MatOfPoint? {
        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(imageMat, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        var maxArea = 0.0
        var bestContour: MatOfPoint? = null
        for (c in contours) {
            val area = Imgproc.contourArea(c)
            if (area > 2000) {
                val peri = Imgproc.arcLength(MatOfPoint2f(*c.toArray()), true)
                val approx = MatOfPoint2f()
                // Tolerância aumentada para 0.04
                Imgproc.approxPolyDP(MatOfPoint2f(*c.toArray()), approx, 0.04 * peri, true)
                if (approx.rows() == 4 && area > maxArea) {
                    maxArea = area
                    bestContour = MatOfPoint(*approx.toArray())
                }
            }
        }
        return bestContour
    }

    private fun warpPerspective(src: Mat, contour: MatOfPoint): Mat {
        val ordered = orderPoints(contour.toArray().map { Point(it.x, it.y) }.toTypedArray())
        val srcPts = MatOfPoint2f(*ordered)
        val tl = ordered[0]; val tr = ordered[1]; val br = ordered[2]; val bl = ordered[3]
        val width = kotlin.math.max(sqrt((br.x-bl.x).pow(2)+(br.y-bl.y).pow(2)), sqrt((tr.x-tl.x).pow(2)+(tr.y-tl.y).pow(2)))
        val height = kotlin.math.max(sqrt((tr.x-br.x).pow(2)+(tr.y-br.y).pow(2)), sqrt((tl.x-bl.x).pow(2)+(tl.y-bl.y).pow(2)))
        val dstPts = MatOfPoint2f(Point(0.0,0.0), Point(width-1,0.0), Point(width-1,height-1), Point(0.0,height-1))
        val transform = Imgproc.getPerspectiveTransform(srcPts, dstPts)
        val dst = Mat()
        Imgproc.warpPerspective(src, dst, transform, Size(width, height))
        return dst
    }

    private fun orderPoints(pts: Array<Point>): Array<Point> {
        val sortedX = pts.sortedBy { it.x }
        val left = sortedX.take(2).sortedBy { it.y }
        val right = sortedX.takeLast(2).sortedBy { it.y }
        return arrayOf(left[0], right[0], right[1], left[1])
    }
}
