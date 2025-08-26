package com.sample.edgedetection.scan
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Point
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.Camera
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.SurfaceHolder
import android.widget.Toast
import com.sample.edgedetection.EdgeDetectionHandler
import com.sample.edgedetection.REQUEST_CODE
import com.sample.edgedetection.SourceManager
import com.sample.edgedetection.crop.CropActivity
import com.sample.edgedetection.processor.Corners
import com.sample.edgedetection.processor.processPicture
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Core.ROTATE_90_CLOCKWISE
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import android.util.Size as SizeB
import android.graphics.PointF
import java.io.FileOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.hypot


class ScanPresenter constructor(
    private val context: Context,
    private val iView: IScanView.Proxy,
    private val initialBundle: Bundle
) :
    SurfaceHolder.Callback, Camera.PictureCallback, Camera.PreviewCallback {
    private val TAG: String = "ScanPresenter ------------>"
    private var mCamera: Camera? = null
    private val mSurfaceHolder: SurfaceHolder = iView.getSurfaceView().holder
    private val executor: ExecutorService
    private val proxySchedule: Scheduler
    private var busy: Boolean = false
    private var mCameraLensFacing: String? = null
    private var flashEnabled: Boolean = false
    private var resultListener: ((String) -> Unit)? = null

    // Auto-capture controls
    private val autoEnabled = AtomicBoolean(true)
    private var minStableFrames = 5
    private var maxCornerMovementPx = 20f   // if corners move less than this between frames
    private var minQuadAreaRatio = 0.10f    // polygon must cover >= 10% of preview

    // Auto-capture state
    private var lastCorners: Corners? = null
    private var stableCounter = 0
    private var lastAutoCaptureAt = 0L
    private var isCapturing = AtomicBoolean(false)

    private val customSaveTo: String? = initialBundle.getString("save_to")

    private var mLastClickTime = 0L
    private var shutted: Boolean = true

    init {
        mSurfaceHolder.addCallback(this)
        executor = Executors.newSingleThreadExecutor()
        proxySchedule = Schedulers.from(executor)
    }

    data class CaptureResult(
        val imagePath: String,
        val width: Int,
        val height: Int,
        val corners: List<PointF>,          // absolute pixel coords in saved image space
        val cornersNormalized: List<PointF> // 0..1 normalized, handy for Flutter overlay
    )

    private var captureResultListener: ((CaptureResult) -> Unit)? = null
    fun setOnCaptureResultListener(listener: (CaptureResult) -> Unit) {
        captureResultListener = listener
    }

    fun setOnResultListener(listener: (String) -> Unit) {
        resultListener = listener
    }

    // after the code that finishes the crop & writes the file (where currently the CropActivity would finish):
    fun notifySaved(path: String) {
        resultListener?.invoke(path)
    }

    fun setAutoCaptureEnabled(enabled: Boolean) {
        autoEnabled.set(enabled)
        stableCounter = 0
    }

    fun setAutoCaptureStability(minFrames: Int, maxCornerMove: Float, minAreaRatio: Float) {
        minStableFrames = minFrames
        maxCornerMovementPx = maxCornerMove
        minQuadAreaRatio = minAreaRatio
    }

    private fun isOpenRecently(): Boolean {
        if (SystemClock.elapsedRealtime() - mLastClickTime < 3000) {
            return true
        }
        mLastClickTime = SystemClock.elapsedRealtime()
        return false
    }

    fun start() {
        mCamera?.startPreview() ?:
        Log.i(TAG, "mCamera startPreview")
    }

    fun stop() {
        mCamera?.stopPreview() ?:
        Log.i(TAG, "mCamera stopPreview")
    }

    val canShut: Boolean get() = shutted

    fun shut() {
        if (isOpenRecently()) {
            Log.i(TAG, "NOT Taking click")
            return
        }
        busy = true
        shutted = false
        Log.i(TAG, "try to focus")

        mCamera?.autoFocus { b, _ ->
            Log.i(TAG, "focus result: $b")
            mCamera?.enableShutterSound(true)
            mCamera?.takePicture(null, null, this)
            Log.i(TAG, "Picture taken")
            isCapturing.set(false)
        }

    }

    fun toggleFlash() {
        try {
            flashEnabled = !flashEnabled
            val parameters = mCamera?.parameters
            parameters?.flashMode =
                if (flashEnabled) Camera.Parameters.FLASH_MODE_TORCH else Camera.Parameters.FLASH_MODE_OFF
            mCamera?.parameters = parameters
            mCamera?.startPreview()
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun updateCamera() {
        if (null == mCamera) {
            return
        }
        mCamera?.stopPreview()
        try {
            mCamera?.setPreviewDisplay(mSurfaceHolder)
        } catch (e: IOException) {
            e.printStackTrace()
            return
        }
        mCamera?.setPreviewCallback(this)
        mCamera?.startPreview()
    }

    fun manualCapture() {
        if (isCapturing.get()) return
        shut() // reuse existing takePicture flow; in onPictureTaken we’ll emit result instead of navigating
        Log.i(TAG, "Image Shutttt !!!!!!!!!")
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private fun getCameraCharacteristics(id: String): CameraCharacteristics {
        return cameraManager.getCameraCharacteristics(id)
    }

    private fun getBackFacingCameraId(): String? {
        for (camID in cameraManager.cameraIdList) {
            val lensFacing =
                getCameraCharacteristics(camID)?.get(CameraCharacteristics.LENS_FACING)!!
            if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                mCameraLensFacing = camID
                break
            }
        }
        return mCameraLensFacing
    }

    private fun initCamera() {

        try {
            mCamera = Camera.open(Camera.CameraInfo.CAMERA_FACING_BACK)
        } catch (e: RuntimeException) {
            e.stackTrace
            Toast.makeText(context, "cannot open camera, please grant camera", Toast.LENGTH_SHORT)
                .show()
            return
        }

        val cameraCharacteristics =
            cameraManager.getCameraCharacteristics(getBackFacingCameraId()!!)

        val size = iView.getCurrentDisplay()?.let {
            getPreviewOutputSize(
                it, cameraCharacteristics, SurfaceHolder::class.java
            )
        }

        Log.i(TAG, "Selected preview size: ${size?.width}${size?.height}")

        size?.width?.toString()?.let { Log.i(TAG, it) }
        val param = mCamera?.parameters
        param?.setPreviewSize(size?.width ?: 1920, size?.height ?: 1080)
        val display = iView.getCurrentDisplay()
        val point = Point()

        display?.getRealSize(point)

        val displayWidth = minOf(point.x, point.y)
        val displayHeight = maxOf(point.x, point.y)
        val displayRatio = displayWidth.div(displayHeight.toFloat())
        val previewRatio = size?.height?.toFloat()?.div(size.width.toFloat()) ?: displayRatio
        if (displayRatio > previewRatio) {
            val surfaceParams = iView.getSurfaceView().layoutParams
            surfaceParams.height = (displayHeight / displayRatio * previewRatio).toInt()
            iView.getSurfaceView().layoutParams = surfaceParams
        }

        val supportPicSize = mCamera?.parameters?.supportedPictureSizes
        supportPicSize?.sortByDescending { it.width.times(it.height) }
        var pictureSize = supportPicSize?.find {
            it.height.toFloat().div(it.width.toFloat()) - previewRatio < 0.01
        }

        if (null == pictureSize) {
            pictureSize = supportPicSize?.get(0)
        }

        if (null == pictureSize) {
            Log.e(TAG, "can not get picture size")
        } else {
            param?.setPictureSize(pictureSize.width, pictureSize.height)
        }
        val pm = context.packageManager
        if (pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_AUTOFOCUS) && mCamera!!.parameters.supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE))
        {
            param?.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
            Log.i(TAG, "enabling autofocus")
        } else {
            Log.i(TAG, "autofocus not available")
        }

        param?.flashMode = Camera.Parameters.FLASH_MODE_OFF

        mCamera?.parameters = param
        mCamera?.setDisplayOrientation(90)
        mCamera?.enableShutterSound(false)
    }

    private fun matrixResizer(sourceMatrix: Mat): Mat {
        val sourceSize: Size = sourceMatrix.size()
        var copied = Mat()
        if (sourceSize.height < sourceSize.width) {
            Core.rotate(sourceMatrix, copied, ROTATE_90_CLOCKWISE)
        } else {
            copied = sourceMatrix
        }
        val copiedSize: Size = copied.size()
        return if (copiedSize.width > ScanConstants.MAX_SIZE.width || copiedSize.height > ScanConstants.MAX_SIZE.height) {
            var useRatio = 0.0
            val widthRatio: Double = ScanConstants.MAX_SIZE.width / copiedSize.width
            val heightRatio: Double = ScanConstants.MAX_SIZE.height / copiedSize.height
            useRatio = if(widthRatio > heightRatio)  widthRatio else heightRatio
            val resizedImage = Mat()
            val newSize = Size(copiedSize.width * useRatio, copiedSize.height * useRatio)
            Imgproc.resize(copied, resizedImage, newSize)
            resizedImage
        } else {
            copied
        }
    }

    fun detectEdge(pic: Mat) {
        // 1) Resize to our working size (what the pipeline already used)
        val resizedMat = matrixResizer(pic)

        // 2) Detect corners on the SAME mat we will save
        val detected = processPicture(resizedMat) // Corners? with .corners = 4 points

        // 3) Save the resizedMat as JPEG (this will be the base for Flutter’s crop screen)
        //    Ensure we write a 3-channel or ARGB_8888 bitmap cleanly.
        val outFile = run {
            val explicitPath = initialBundle.getString(EdgeDetectionHandler.SAVE_TO)
            if (!explicitPath.isNullOrBlank()) File(explicitPath)
            else File((context as Activity).cacheDir, "scan_${System.currentTimeMillis()}.jpg")
        }

        // Convert to Bitmap and write JPEG (reliable for BGRA/RGBA mats)
        val saveBmp = android.graphics.Bitmap.createBitmap(
            resizedMat.width(), resizedMat.height(), android.graphics.Bitmap.Config.ARGB_8888
        )
        // Many camera previews are in BGR/RGBA; your code earlier used COLOR_RGB2BGRA.
        // For safety, convert to BGRA if needed; then matToBitmap handles it well.
        var saveMat = Mat()
        when (resizedMat.channels()) {
            1 -> Imgproc.cvtColor(resizedMat, saveMat, Imgproc.COLOR_GRAY2BGRA)
            3 -> Imgproc.cvtColor(resizedMat, saveMat, Imgproc.COLOR_BGR2BGRA)
            else -> saveMat = resizedMat
        }
        Utils.matToBitmap(saveMat, saveBmp, true)

        FileOutputStream(outFile).use { fos ->
            saveBmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, fos)
            fos.flush()
        }
//        saveBmp.recycle()
        if (saveMat !== resizedMat) saveMat.release()
        resizedMat.release()
        pic.release()

        // 4) Package corners (in the same pixel space as the saved image)
        val width = saveBmp.width
        val height = saveBmp.height

        val pxCorners: List<PointF> = detected?.corners?.map { p ->
            // org.opencv.core.Point -> android.graphics.PointF
            PointF(p?.x?.toFloat() ?: 0f, p?.y!!.toFloat() ?: 0f)
        } ?: emptyList()

        val normalized: List<PointF> = pxCorners.map { pf ->
            PointF(pf.x / width.toFloat(), pf.y / height.toFloat())
        }

        // 5) Notify listener back to the PlatformView
        captureResultListener?.invoke(
            CaptureResult(
                imagePath = outFile.absolutePath,
                width = width,
                height = height,
                corners = pxCorners,
                cornersNormalized = normalized
            )
        )
    }


//    fun detectEdge(pic: Mat) {
//        Log.i("height", pic.size().height.toString())
//        Log.i("width", pic.size().width.toString())
//        val resizedMat = matrixResizer(pic)
//        SourceManager.corners = processPicture(resizedMat)
//        Imgproc.cvtColor(resizedMat, resizedMat, Imgproc.COLOR_RGB2BGRA)
//        SourceManager.pic = resizedMat
//        val cropIntent = Intent(context, CropActivity::class.java)
//        cropIntent.putExtra(EdgeDetectionHandler.INITIAL_BUNDLE, this.initialBundle)
//        (context as Activity).startActivityForResult(cropIntent, REQUEST_CODE)
//    }

    override fun surfaceCreated(p0: SurfaceHolder) {
        initCamera()
    }

    override fun surfaceChanged(p0: SurfaceHolder, p1: Int, p2: Int, p3: Int) {
        updateCamera()
    }

    override fun surfaceDestroyed(p0: SurfaceHolder) {
        synchronized(this) {
            mCamera?.stopPreview()
            mCamera?.setPreviewCallback(null)
            mCamera?.release()
            mCamera = null
        }
    }

    override fun onPictureTaken(p0: ByteArray?, p1: Camera?) {
        Log.i(TAG, "on picture taken")
        Observable.just(p0)
            .subscribeOn(proxySchedule)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe {
                val pictureSize = p1?.parameters?.pictureSize
                Log.i(TAG, "picture size: " + pictureSize.toString())
                val mat = Mat(
                    Size(
                        pictureSize?.width?.toDouble() ?: 1920.toDouble(),
                        pictureSize?.height?.toDouble() ?: 1080.toDouble()
                    ), CvType.CV_8U
                )
                mat.put(0, 0, p0)
                val pic = Imgcodecs.imdecode(mat, Imgcodecs.CV_LOAD_IMAGE_UNCHANGED)
                Core.rotate(pic, pic, Core.ROTATE_90_CLOCKWISE)
                mat.release()
                detectEdge(pic)

//                processPicture(pic)
//                emitCaptureResult(pic, corners)

                shutted = true
                busy = false
            }
    }

    override fun onPreviewFrame(p0: ByteArray?, p1: Camera?) {
        if (busy) {
            return
        }
        busy = true
        try {
            Observable.just(p0)
                .observeOn(proxySchedule)
                .doOnError {}
                .subscribe({
                    val parameters = p1?.parameters
                    val width = parameters?.previewSize?.width
                    val height = parameters?.previewSize?.height
                    val yuv = YuvImage(
                        p0, parameters?.previewFormat ?: 0, width ?: 1080, height
                            ?: 1920, null
                    )
                    val out = ByteArrayOutputStream()
                    yuv.compressToJpeg(Rect(0, 0, width ?: 1080, height ?: 1920), 100, out)
                    val bytes = out.toByteArray()
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    val img = Mat()
                    Utils.bitmapToMat(bitmap, img)
                    bitmap.recycle()
                    Core.rotate(img, img, Core.ROTATE_90_CLOCKWISE)
                    try {
                        out.close()
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }

                    val corner = processPicture(img)
                    Log.e(TAG, "PREVIEWING FRAAAAAMMMMMEEEEEE")
                    Observable.create<Corners> {
                        busy = false
                        if ( corner != null && corner.corners.size == 4) {
                            it.onNext(corner)
                        } else {
                            it.onError(Throwable("paper not detected"))
                        }
                    }.observeOn(AndroidSchedulers.mainThread())
                        .subscribe({
                            iView.getPaperRect().onCornersDetected(it)

                        }, {
                            iView.getPaperRect().onCornersNotDetected()
                        })


                    // -------- Auto-capture logic --------
                    if (autoEnabled.get() && corner != null && corner.corners.size == 4 && !isCapturing.get()) {
                        Log.e(TAG, "ABOUT TO CHECK AUTOCAP")
                        Log.e(TAG, "STABLE COUNT =======> " + stableCounter.toString())
                        val okArea = quadAreaRatio(corner, img.width().toFloat(), img.height().toFloat()) >= minQuadAreaRatio
                        val stable = isStable(lastCorners, corner, maxCornerMovementPx)
                        if (okArea && stable) stableCounter++ else stableCounter = 0

                        if (stableCounter >= minStableFrames ) {
                            lastAutoCaptureAt = System.currentTimeMillis()
                            // save current preview frame (or trigger takePicture for full-res)
                            isCapturing.set(true)

                            // Option A: trigger full-res picture
                            mCamera?.autoFocus { _, _ ->
                                Log.e(TAG, "WANTING TO CAPPTURREE ----->>>")

                                shut()
//                                mCamera?.takePicture(null, null, this)
                                // onPictureTaken will emit & release isCapturing
                                isCapturing.set(false)
                            }
//                            shut()
//                            isCapturing.set(false)
                        }
                    }

                    lastCorners = corner

                    busy = false
                }, { throwable -> Log.e(TAG, throwable.message?: "preview error")
                    busy = false
                })
        } catch (e: Exception) {
            Log.e(TAG, "onPreviewFrame error: ${e.message}")
            busy = false
        }

    }

    /** [CameraCharacteristics] corresponding to the provided Camera ID */

    class SmartSize(width: Int, height: Int) {
        var size = SizeB(width, height)
        var long = max(size.width, size.height)
        var short = min(size.width, size.height)
        override fun toString() = "SmartSize(${long}x${short})"
    }

    /** Standard High Definition size for pictures and video */
    private val SIZE_1080P: SmartSize = SmartSize(1920, 1080)

    /** Returns a [SmartSize] object for the given [Display] */
    private fun getDisplaySmartSize(display: Display): SmartSize {
        val outPoint = Point()
        display.getRealSize(outPoint)
        return SmartSize(outPoint.x, outPoint.y)
    }

    /**
     * Returns the largest available PREVIEW size. For more information, see:
     * https://d.android.com/reference/android/hardware/camera2/CameraDevice and
     * https://developer.android.com/reference/android/hardware/camera2/params/StreamConfigurationMap
     */
    private fun <T> getPreviewOutputSize(
        display: Display,
        characteristics: CameraCharacteristics,
        targetClass: Class<T>,
        format: Int? = null
    ): SizeB {

        // Find which is smaller: screen or 1080p
        val screenSize = getDisplaySmartSize(display)
        val hdScreen = screenSize.long >= SIZE_1080P.long || screenSize.short >= SIZE_1080P.short
        val maxSize = if (hdScreen) SIZE_1080P else screenSize

        // If image format is provided, use it to determine supported sizes; else use target class
        val config = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        )!!
        if (format == null)
            assert(StreamConfigurationMap.isOutputSupportedFor(targetClass))
        else
            assert(config.isOutputSupportedFor(format))
        val allSizes = if (format == null)
            config.getOutputSizes(targetClass) else config.getOutputSizes(format)

        // Get available sizes and sort them by area from largest to smallest
        val validSizes = allSizes
            .sortedWith(compareBy { it.height * it.width })
            .map { SmartSize(it.width, it.height) }.reversed()

        // Then, get the largest output size that is smaller or equal than our max size
        return validSizes.first { it.long <= maxSize.long && it.short <= maxSize.short }.size
    }

    private fun isStable(prev: Corners?, cur: Corners, maxMove: Float): Boolean {
        if (prev == null) return false
        // order assumed TL, TR, BR, BL in your Corners
        var total = 0f
        for (i in 0 until 4) {
            val dx = (cur!!.corners[i]!!.x - prev!!.corners[i]!!.x).toFloat()
            val dy = (cur!!.corners[i]!!.y - prev!!.corners[i]!!.y).toFloat()
            total += hypot(dx.toDouble(), dy.toDouble()).toFloat()
        }
        val avg = total / 4f
        Log.e(TAG, "STABILITY AVERAGE ======" + avg.toString())
        val stability = avg <= maxMove
        Log.e(TAG, "STABILITY Result ======" + stability.toString())
        return true
    }

    private fun quadAreaRatio(c: Corners, w: Float, h: Float): Float {
        if(c == null)return 0f
        // polygon area / (w*h). Quick shoelace area:
        val pts = c.corners
        val xs = pts!!.map { it!!.x.toFloat() }
        val ys = pts!!.map { it!!.y.toFloat() }
        val area = 0.5f * kotlin.math.abs(
            xs[0]*ys[1] + xs[1]*ys[2] + xs[2]*ys[3] + xs[3]*ys[0]
                    - ys[0]*xs[1] - ys[1]*xs[2] - ys[2]*xs[3] - ys[3]*xs[0]
        )

        val value = area / (w * h)
        Log.e(TAG, "QUAD AREA RATIO ======" + value.toString())
        return value
    }

//    private fun emitCaptureResult(mat: Mat, cornersPreviewSpace: Corners?)
//    {
//        try {
//            // 1) Save the full-res image (JPEG)
//            val outFile = chooseOutFile()
//            Imgcodecs.imwrite(outFile.absolutePath, mat)
//
//            // 2) Compute corners in full-res space.
//            //    If cornersPreviewSpace is from full-res (onPictureTaken), great.
//            //    If from preview, map them up (optional). For simplicity, when we emit
//            //    from onPictureTaken() we pass corners computed at full-res.
//            val corners = cornersPreviewSpace ?: processPicture(mat)
//
//            val list = (corners?.corners ?: emptyList()).map { p ->
//                PointF(p.x.toFloat(), p.y.toFloat())
//            }
//
//            onCaptureResult?.invoke(
//                CaptureResult(
//                    imagePath = outFile.absolutePath,
//                    corners = list
//                )
//            )
//        } catch (e: Exception) {
//            Log.e(TAG, "emitCaptureResult failed: ${e.message}")
//        }
//    }

    private fun chooseOutFile(): File {
        customSaveTo?.takeIf { it.isNotBlank() }?.let { return File(it) }
        val dir = context.getExternalFilesDir("scans") ?: context.filesDir
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "scan_${System.currentTimeMillis()}.jpg")
    }

    fun release() {
        // clean up if needed
    }

}
