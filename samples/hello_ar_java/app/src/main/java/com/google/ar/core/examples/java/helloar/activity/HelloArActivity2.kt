package com.google.ar.core.examples.java.helloar.activity

import android.content.DialogInterface
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Bundle
import android.util.Log
import android.util.Pair
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager
import com.google.ar.core.*
import com.google.ar.core.ArCoreApk.InstallStatus
import com.google.ar.core.Config.InstantPlacementMode
import com.google.ar.core.examples.java.helloar.R
import com.google.ar.core.examples.java.helloar.common.helpers.*
import com.google.ar.core.examples.java.helloar.common.rendering.AugmentedImageRenderer
import com.google.ar.core.examples.java.helloar.common.samplerender.*
import com.google.ar.core.examples.java.helloar.common.samplerender.arcore.BackgroundRenderer
import com.google.ar.core.examples.java.helloar.common.samplerender.arcore.PlaneRenderer
import com.google.ar.core.examples.java.helloar.common.samplerender.arcore.SpecularCubemapFilter
import com.google.ar.core.examples.java.helloar.common.utils.ToastUtil
import com.google.ar.core.exceptions.*
import kotlinx.android.synthetic.main.activity_main2.*
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.*

/**
 * This is a simple example that shows how to create an augmented reality (AR) application using the
 * ARCore API. The application will display any detected planes and will allow the user to tap on a
 * plane to place a 3D model.
 */
class HelloArActivity2 : AppCompatActivity(), SampleRender.Renderer {
    // Rendering. The Renderers are created here, and initialized when the GL surface is created.
    private var surfaceView: GLSurfaceView? = null
    private var installRequested = false
    private var session: Session? = null
    private val messageSnackbarHelper = SnackbarHelper()
    private var displayRotationHelper: DisplayRotationHelper? = null
    private val trackingStateHelper = TrackingStateHelper(this)
    private var tapHelper: TapHelper? = null
    private var render: SampleRender? = null
    private var planeRenderer: PlaneRenderer? = null
    private var backgroundRenderer: BackgroundRenderer? = null
    private var virtualSceneFramebuffer: Framebuffer? = null
    private var hasSetTextureNames = false
    private val depthSettings = DepthSettings()
    private val depthSettingsMenuDialogCheckboxes = BooleanArray(2)
    private val instantPlacementSettings = InstantPlacementSettings()
    private val instantPlacementSettingsMenuDialogCheckboxes = BooleanArray(1)

    // Point Cloud
    private var pointCloudVertexBuffer: VertexBuffer? = null
    private var pointCloudMesh: Mesh? = null
    private var pointCloudShader: Shader? = null

    //保持点云的最后渲染结果从而避免点云没有改变却更新了 VBO (顶点缓冲对象（Vertex Buffer Objects，VBO）)，因为无法比较点云对象所以只能使用时间戳来比较
    private var lastPointCloudTimestamp: Long = 0

    // 虚拟对象 (ARCore 实例)
    private var virtualObjectMesh: Mesh? = null
    private var virtualObjectShader: Shader? = null
    private val anchors = ArrayList<Anchor>()

    // 环境的 HDR（高动态范围成像）
    private var dfgTexture: Texture? = null
    private var cubemapFilter: SpecularCubemapFilter? = null

    // 在这里分配的临时矩阵，以减少每个帧的分配数量
    private val modelMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val modelViewMatrix = FloatArray(16) // view x model
    private val modelViewProjectionMatrix = FloatArray(16) // projection x view x model
    private val sphericalHarmonicsCoefficients = FloatArray(9 * 3)
    private val viewInverseMatrix = FloatArray(16)
    private val worldLightDirection = floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f)
    private val viewLightDirection = FloatArray(4) // view x world light direction

//    private val augmentedImageRenderer: AugmentedImageRenderer = AugmentedImageRenderer()
//
    private var shouldConfigureSession = false
//
    // 如果为 true 则使用 assets 的本地图片，为 false 使用预生成的 .imgdb 图片数据库文件
    private val useSingleImage = false
//
//    // Augmented image and its associated center pose anchor, keyed by index of the augmented image in the database.
//    // 增强图像和它关联的中心姿态锚点，由数据库中的增强图像的索引设置为 key
//    private val augmentedImageMap: HashMap<Int, Pair<AugmentedImage, Anchor>> = HashMap()
//
//    private var glideRequestManager: RequestManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main2)

        surfaceView = findViewById(R.id.surfaceview)
        displayRotationHelper = DisplayRotationHelper( /*context=*/this)

        // Set up touch listener.
        tapHelper = TapHelper( /*context=*/this)
        surfaceView?.setOnTouchListener(tapHelper)

        // Set up renderer.
        render = SampleRender(surfaceView, this, assets)

        depthSettings.onCreate(this)
        instantPlacementSettings.onCreate(this)

//        glideRequestManager = Glide.with(this)
//        glideRequestManager
//                ?.load(Uri.parse("file:///android_asset/fit_to_scan.png"))
//                ?.into(image_view_fit_to_scan)

        installRequested = false
    }


    override fun onDestroy() {
        // 显式关闭 ARCore 会话以释放本地资源, 在应用程序中调用 close() 之前，请查看 API 引用以了解重要的注意事项和更复杂的生命周期要求：
        // https://developers.google.com/ar/reference/java/arcore/reference/com/google/ar/core/Session#close()
        session?.close()
        session = null
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()

        if (session == null) {
            if (!createSession()) {
                return
            }
            shouldConfigureSession = true
        }

        // 注意：顺序很重要
        try {
            if (shouldConfigureSession) {
                configureSession()
                shouldConfigureSession = false
            }
            // 为了录制实时摄像头会话以便之后播放，可以在任意时间调用 session.startRecording(recorderConfig) 方法
            // 为了回放一个之前记录 AR 会话来替代使用实时摄像头，在调用 session.resume() 方法之前调用 session.setPlaybackDataset(playbackDatasetPath) 方法
            // 更多资料见：https://developers.google.com/ar/develop/java/recording-and-playback
            session!!.resume()
        } catch (e: CameraNotAvailableException) {
            messageSnackbarHelper.showError(this, "Camera not available. Try restarting the app.")
            session = null
            return
        }
        surfaceView!!.onResume()
        displayRotationHelper!!.onResume()

        image_view_fit_to_scan.visibility = View.VISIBLE
    }

    public override fun onPause() {
        super.onPause()
        if (session != null) {
            // 请注意，顺序很重要，GLSurfaceView 首先被暂停，因此它不会去尝试查询 session，如果 session 在 GLSurfaceView 之前被暂停，则 GLSurfaceView 可能
            // 仍然调用 session.update() 并得到一个 SessionPausedException
            displayRotationHelper?.onPause()
            surfaceView?.onPause()
            session?.pause()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
            // Use toast instead of snackbar here since the activity will exit.
            ToastUtil.showShortToast("Camera permission is needed to run this application")
            if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
                // Permission denied with checking "Do not ask again".
                CameraPermissionHelper.launchPermissionSettings(this)
            }
            finish()
        } else {
            Log.d(TAG, "onRequestPermissionsResult CameraPermissionHelper hasCameraPermission")
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus)
    }

    override fun onSurfaceCreated(render: SampleRender) {
        // Prepare the rendering objects. This involves reading shaders and 3D model files, so may throw
        // an IOException.
        //准备渲染对象，这涉及到读取 着色器 和 三维模型文件，因此可能会抛出一个 IOException
        try {
            planeRenderer = PlaneRenderer(render)
            backgroundRenderer = BackgroundRenderer(render)
            virtualSceneFramebuffer = Framebuffer(render,  /*width=*/1,  /*height=*/1)

            // 镜面反射滤光片
            cubemapFilter = SpecularCubemapFilter(render, CUBEMAP_RESOLUTION, CUBEMAP_NUMBER_OF_IMPORTANCE_SAMPLES)

            addDfgTexture()
            createPointCloud()

            // 要渲染的虚拟对象 (ARCore pawn)
            val virtualObjectAlbedoTexture = createVirtualObjectAlbedoTexture()
            val virtualObjectPbrTexture = createVirtualObjectPbrTexture()
            virtualObjectMesh = createVirtualObjectMesh()
            virtualObjectShader = createVirtualObjectShader()
                    .setTexture("u_AlbedoTexture", virtualObjectAlbedoTexture)
                    .setTexture("u_RoughnessMetallicAmbientOcclusionTexture", virtualObjectPbrTexture)
                    .setTexture("u_Cubemap", cubemapFilter!!.filteredCubemapTexture)
                    .setTexture("u_DfgTexture", dfgTexture)

//            augmentedImageRenderer.createOnGlThread( /*context=*/this)

        } catch (e: IOException) {
            Log.e(TAG, "Failed to read a required asset file", e)
            messageSnackbarHelper.showError(this, "Failed to read a required asset file: $e")
        }
    }

    private fun addDfgTexture() {
        // 为了环境灯光加载 DFG 查找表
        dfgTexture = Texture(render, Texture.Target.TEXTURE_2D, Texture.WrapMode.CLAMP_TO_EDGE, false)
        // dfg.raw 是一个原始的双通道半浮点的纹理文件
        val dfgResolution = 64
        val dfgChannels = 2
        val halfFloatSize = 2
        val buffer = ByteBuffer.allocateDirect(dfgResolution * dfgResolution * dfgChannels * halfFloatSize)
        var inputStreamObj = assets.open("models/dfg.raw")
        inputStreamObj.read(buffer.array())

        //GLES30 是 openGl ES 3.0，用来添加纹理
        // SampleRender abstraction 在这里发生泄漏
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, dfgTexture!!.textureId)
        GLError.maybeThrowGLException("Failed to bind DFG texture", "glBindTexture")
        GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D,  /*level=*/
                0,
                GLES30.GL_RG16F,  /*width=*/
                dfgResolution,  /*height=*/
                dfgResolution,  /*border=*/
                0,
                GLES30.GL_RG,
                GLES30.GL_HALF_FLOAT,
                buffer)
        GLError.maybeThrowGLException("Failed to populate DFG texture", "glTexImage2D")
    }

    private fun createPointCloud() {
        // 点云
        pointCloudShader = Shader.createFromAssets(
                render, "shaders/point_cloud.vert", "shaders/point_cloud.frag",  /*defines=*/null)
                .setVec4("u_Color", floatArrayOf(31.0f / 255.0f, 188.0f / 255.0f, 210.0f / 255.0f, 1.0f))
                .setFloat("u_PointSize", 5.0f)

        // 每个顶点有四个条目：X，Y，Z，置信度
        pointCloudVertexBuffer = VertexBuffer(render,  /*numberOfEntriesPerVertex=*/4,  /*entries=*/null)
        val pointCloudVertexBuffers = arrayOf(pointCloudVertexBuffer!!)
        pointCloudMesh = Mesh(render, Mesh.PrimitiveMode.POINTS,  /*indexBuffer=*/null, pointCloudVertexBuffers)
    }

    private fun createVirtualObjectAlbedoTexture(): Texture {
        return Texture.createFromAsset(render, "models/pawn_albedo.png", Texture.WrapMode.CLAMP_TO_EDGE, Texture.ColorFormat.SRGB)
    }

    private fun createVirtualObjectPbrTexture(): Texture {
        return Texture.createFromAsset(render, "models/pawn_roughness_metallic_ao.png", Texture.WrapMode.CLAMP_TO_EDGE, Texture.ColorFormat.LINEAR)
    }

    private fun createVirtualObjectMesh(): Mesh {
        return Mesh.createFromAsset(render, "models/pawn.obj")
    }

    private fun createVirtualObjectShader(): Shader {
        return Shader.createFromAssets(
                render,
                "shaders/environmental_hdr.vert",
                "shaders/environmental_hdr.frag",  /*defines=*/
                object : HashMap<String?, String?>() {
                    init {
                        put("NUMBER_OF_MIPMAP_LEVELS", Integer.toString(cubemapFilter!!.numberOfMipmapLevels))
                    }
                })
    }

    override fun onSurfaceChanged(render: SampleRender, width: Int, height: Int) {
        Log.d(TAG, "--- onSurfaceChanged ---")
        displayRotationHelper?.onSurfaceChanged(width, height)
        virtualSceneFramebuffer?.resize(width, height)
    }

    override fun onDrawFrame(render: SampleRender) {
        Log.d(TAG, "--- onDrawFrame ---")
        if (session == null) {
            return
        }
        // 纹理名称应当只能在 GL 线程设置一次除非他们发生改变，在 onDrawFrame() 中执行而不是在 onSurfaceCreated() 中执行，因为不能保证在 onSurfaceCreated() 中执行时 session 初始化完成
        if (!hasSetTextureNames) {
            session?.setCameraTextureNames(intArrayOf(backgroundRenderer!!.cameraColorTexture.textureId))
            hasSetTextureNames = true
        }

        // 更新每一帧的状态

        // 通知 ARCore session ，view 的 size 已经发生了改变，以便透视矩阵和视频背景可以适当调整
        displayRotationHelper?.updateSessionIfNeeded(session)

        val frame: Frame = getFrame() ?: return
        val camera = frame.camera

        handleBackgroundRenderer(frame, camera)

        // 处理每帧的一次点击
        handleTap(frame, camera)

        // 跟踪时保持屏幕解锁，但在跟踪停止时允许其锁定。
        trackingStateHelper.updateKeepScreenOnFlag(camera.trackingState)

        showTrackingMessage(camera)

        // 画背景
        if (frame.timestamp != 0L) {
            // 如果摄像机尚未生成第一帧则抑制渲染，这是为了避免如果 texture 被重用了，却绘制了可能来自之前 session 的残余数据
            backgroundRenderer!!.drawBackground(render)
        }

        // 如果没有跟踪则不要绘制 3D 对象
        if (camera.trackingState == TrackingState.PAUSED) {
            return
        }

        drawNonOccludedVirtualObjects(frame, camera)
        drawOccludedVirtualObjects(frame)


        // Get camera matrix and draw.
        val viewmtx = FloatArray(16)
        camera.getViewMatrix(viewmtx, 0)

        // Compute lighting from average intensity of the image.
        val colorCorrectionRgba = FloatArray(4)
        frame.lightEstimate.getColorCorrection(colorCorrectionRgba, 0)

        // Visualize augmented images.
//        drawAugmentedImages(frame, projectionMatrix, viewmtx, colorCorrectionRgba)
    }

//    private fun drawAugmentedImages(frame: Frame, projmtx: FloatArray, viewmtx: FloatArray, colorCorrectionRgba: FloatArray) {
//        val updatedAugmentedImages = frame.getUpdatedTrackables(AugmentedImage::class.java)
//
//        // Iterate to update augmentedImageMap, remove elements we cannot draw.
//        for (augmentedImage in updatedAugmentedImages) {
//            when (augmentedImage.trackingState) {
//                TrackingState.PAUSED -> {
//                    // When an image is in PAUSED state, but the camera is not PAUSED, it has been detected,
//                    // but not yet tracked.
//                    val text = String.format("Detected Image %d", augmentedImage.index)
//                    messageSnackbarHelper.showMessage(this, text)
//                }
//                TrackingState.TRACKING -> {
//                    // Have to switch to UI Thread to update View.
//                    runOnUiThread { image_view_fit_to_scan.visibility = View.GONE }
//
//                    // Create a new anchor for newly found images.
//                    if (!augmentedImageMap.containsKey(augmentedImage.index)) {
//                        val centerPoseAnchor = augmentedImage.createAnchor(augmentedImage.centerPose)
//                        augmentedImageMap[augmentedImage.index] = Pair.create(augmentedImage, centerPoseAnchor)
//
//
//                    }
//                }
//                TrackingState.STOPPED -> augmentedImageMap.remove(augmentedImage.index)
//                else -> {
//                }
//            }
//        }
//
//        // Draw all images in augmentedImageMap
//        for (pair in augmentedImageMap.values) {
//            val augmentedImage = pair.first
//            val centerAnchor: Anchor? = augmentedImageMap.get(augmentedImage.index)?.second
//            when (augmentedImage.trackingState) {
//                TrackingState.TRACKING -> {
//                    augmentedImageRenderer.draw(viewmtx, projmtx, augmentedImage, centerAnchor, colorCorrectionRgba)
//                }
//                else -> {
//
//                }
//            }
//        }
//    }

    /**
     * 处理 backgroundRenderer
     */
    private fun handleBackgroundRenderer(frame: Frame, camera: Camera) {
        // 更新 BackgroundRenderer 状态为了匹配深度设置
        try {
            backgroundRenderer?.setUseDepthVisualization(render, depthSettings.depthColorVisualizationEnabled())
            backgroundRenderer?.setUseOcclusion(render, depthSettings.useDepthForOcclusion())
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read a required asset file", e)
            messageSnackbarHelper.showError(this, "Failed to read a required asset file: $e")
            return
        }

        // BackgroundRenderer.updateDisplayGeometry() 方法必须在每帧都被调用去更新坐标用来画背景相机图像
        backgroundRenderer?.updateDisplayGeometry(frame)
        if (camera.trackingState == TrackingState.TRACKING && (depthSettings.useDepthForOcclusion() || depthSettings.depthColorVisualizationEnabled())) {
            try {
                frame.acquireDepthImage().use { depthImage -> backgroundRenderer!!.updateCameraDepthTexture(depthImage) }
            } catch (e: NotYetAvailableException) {
                // 这通常意味着深度数据还不可用。这是正常的，所以我们不会打印 log
            }
        }
    }

    private fun getFrame(): Frame? {
        // 从 ARSession 获取当前帧，当配置被设置为 UpdateMode.BLOCKING (默认) 时，这将限制渲染摄像机的帧速率
        val frame: Frame
        frame = try {
            session!!.update()
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Camera not available during onDrawFrame", e)
            messageSnackbarHelper.showError(this, "Camera not available. Try restarting the app.")
            return null
        }
        return frame
    }

    /**
     * 使用底部的 SnackBar 来显示 message
     */
    private fun showTrackingMessage(camera: Camera) {
        // 显示一条消息基于是否跟踪失败，如果平面被检测到，或者如果用户放置了任何东西
        var message: String? = null
        if (camera.trackingState == TrackingState.PAUSED) {
            message = if (camera.trackingFailureReason == TrackingFailureReason.NONE) {
                SEARCHING_PLANE_MESSAGE
            } else {
                TrackingStateHelper.getTrackingFailureReasonString(camera)
            }
        } else if (hasTrackingPlane()) {
            if (anchors.isEmpty()) {
                message = WAITING_FOR_TAP_MESSAGE
            }
        } else {
            message = SEARCHING_PLANE_MESSAGE
        }
        if (message == null) {
            messageSnackbarHelper.hide(this)
        } else {
            messageSnackbarHelper.showMessage(this, message)
        }
    }

    /**
     *  绘制非遮挡虚拟对象（平面、点云）
     */
    private fun drawNonOccludedVirtualObjects(frame: Frame, camera: Camera) {

        // 获取投影矩阵
        camera.getProjectionMatrix(projectionMatrix, 0, Z_NEAR, Z_FAR)

        // 获取相机矩阵并绘制
        camera.getViewMatrix(viewMatrix, 0)
        frame.acquirePointCloud().use { pointCloud ->
            if (pointCloud.timestamp > lastPointCloudTimestamp) {
                pointCloudVertexBuffer!!.set(pointCloud.getPoints())
                lastPointCloudTimestamp = pointCloud.getTimestamp()
            }
            Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
            pointCloudShader!!.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)
            //绘制点云
            render?.draw(pointCloudMesh, pointCloudShader)
        }

        // 使平面可视化
        planeRenderer?.drawPlanes(
                render,
                session!!.getAllTrackables(Plane::class.java),
                camera.displayOrientedPose,
                projectionMatrix)
    }

    /**
     * 绘制被遮挡的虚拟对象
     */
    private fun drawOccludedVirtualObjects(frame: Frame) {
        // -- 绘制被遮挡的虚拟对象

        // 更新着色器中的照明参数
        updateLightEstimation(frame.lightEstimate, viewMatrix)

        // 通过触摸创建可视化锚点
        render?.clear(virtualSceneFramebuffer, 0f, 0f, 0f, 0f)

        for (anchor in anchors) {
            if (anchor.trackingState != TrackingState.TRACKING) {
                continue
            }

            // 获得世界空间中锚点的当前姿势.
            // 在调用 session.update() 方法时，随着 ARCore 不断改进的估算，锚点姿势随之更新
            anchor.pose.toMatrix(modelMatrix, 0)

            // 计算 模型/视图/投影 矩阵
            Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)
            Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)

            // 更新着色器属性并绘制
            virtualObjectShader?.setMat4("u_ModelView", modelViewMatrix)
            virtualObjectShader?.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)
            render?.draw(virtualObjectMesh, virtualObjectShader, virtualSceneFramebuffer)
        }

        // 用背景构成虚拟场景
        backgroundRenderer!!.drawVirtualScene(render, virtualSceneFramebuffer, Z_NEAR, Z_FAR)
    }

    //每帧只处理一次点击，因为和点击频率经常比帧速率低
    private fun handleTap(frame: Frame, camera: Camera) {
        val tap = tapHelper!!.poll()
        if (tap != null && camera.trackingState == TrackingState.TRACKING) {
            val hitResultList: List<HitResult> = if (instantPlacementSettings.isInstantPlacementEnabled) {
                frame.hitTestInstantPlacement(tap.x, tap.y, APPROXIMATE_DISTANCE_METERS)
            } else {
                frame.hitTest(tap)
            }
            for (hit in hitResultList) {
                // 如果任何平面，定向点，或者 即时放置 被命中，则创建一个锚点
                val trackable = hit.trackable
                //如果一个平面被命中，检查它是否在平面多边形内被击中
                if (
                        (trackable is Plane && trackable.isPoseInPolygon(hit.hitPose) && PlaneRenderer.calculateDistanceToPlane(hit.hitPose, camera.pose) > 0)
                        || (trackable is Point && trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL)
                        || trackable is InstantPlacementPoint
                ) {
                    // 限制创建的对象数，这样可以避免渲染系统和 ARCore 过载
                    if (anchors.size >= 20) {
                        anchors[0].detach()
                        anchors.removeAt(0)
                    }

                    // 添加一个锚点告诉 ARCore 它应当跟踪空间中的这个位置，为了将 3D 模型放在无论是对于世界坐标系还是对于平面都是正确的位置，所以在该平面内创建了一个锚点
                    anchors.add(hit.createAnchor())
                    // 对于支持 Depth API 的设备，显示一个对话框来建议弃用 depth-based 闭合功能，这个对话框需要在 UI 线程产生
                    runOnUiThread { showOcclusionDialogIfNeeded() }

                    // 点击处是按照深度排序的，考虑只在平面、定向点或者即时放置点来来关闭某点击处
                    break
                }
            }
        }
    }

    /**
     * 在第一次调用时显示一个弹出对话框，确定用户是否要启用 depth-based 遮挡，对话框的结果可以通过 useDepthForOcclusion() 来恢复
     */
    private fun showOcclusionDialogIfNeeded() {
        val isDepthSupported = session!!.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
        if (!depthSettings.shouldShowDepthEnableDialog() || !isDepthSupported) {
            return  // 不需要显示对话框
        }

        // Asks the user whether they want to use depth-based occlusion.
        AlertDialog.Builder(this)
                .setTitle(R.string.options_title_with_depth)
                .setMessage(R.string.depth_use_explanation)
                .setPositiveButton(
                        R.string.button_text_enable_depth
                ) { dialog: DialogInterface?, which: Int -> depthSettings.setUseDepthForOcclusion(true) }
                .setNegativeButton(
                        R.string.button_text_disable_depth
                ) { dialog: DialogInterface?, which: Int -> depthSettings.setUseDepthForOcclusion(false) }
                .show()
    }

    /**
     * 检查如果我们是否检测到了至少一个平面
     */
    private fun hasTrackingPlane(): Boolean {
        for (plane in session!!.getAllTrackables<Plane>(Plane::class.java)) {
            if (plane.trackingState == TrackingState.TRACKING) {
                return true
            }
        }
        return false
    }

    /**
     * 基于当前帧的光照估计来更新状态
     */
    private fun updateLightEstimation(lightEstimate: LightEstimate, viewMatrix: FloatArray) {
        if (lightEstimate.state != LightEstimate.State.VALID) {
            virtualObjectShader!!.setBool("u_LightEstimateIsValid", false)
            return
        }
        virtualObjectShader!!.setBool("u_LightEstimateIsValid", true)
        Matrix.invertM(viewInverseMatrix, 0, viewMatrix, 0)
        virtualObjectShader!!.setMat4("u_ViewInverse", viewInverseMatrix)
        updateMainLight(lightEstimate.environmentalHdrMainLightDirection, lightEstimate.environmentalHdrMainLightIntensity, viewMatrix)
        updateSphericalHarmonicsCoefficients(lightEstimate.environmentalHdrAmbientSphericalHarmonics)
        cubemapFilter!!.update(lightEstimate.acquireEnvironmentalHdrCubeMap())
    }

    /**
     * 更新主光源状态
     */
    private fun updateMainLight(direction: FloatArray, intensity: FloatArray, viewMatrix: FloatArray) {
        // 我们需要 vec4 中的方向和 0.0 作为最后的部分来把它转换到 view space
        worldLightDirection[0] = direction[0]
        worldLightDirection[1] = direction[1]
        worldLightDirection[2] = direction[2]
        Matrix.multiplyMV(viewLightDirection, 0, viewMatrix, 0, worldLightDirection, 0)
        virtualObjectShader!!.setVec4("u_ViewLightDirection", viewLightDirection)
        virtualObjectShader!!.setVec3("u_LightIntensity", intensity)
    }

    /**
     * 更新球谐函数系数
     * 球谐函数，是拉普拉斯方程的球坐标系形式解的角度部分，是近代数学的一个著名函数，在量子力学，计算机图形学，渲染光照处理以及球面映射等方面广泛应用。
     * 拉普拉斯方程在球坐标系中的表达式分离变量之后，角度部分的偏微分方程称为球函数方程。
     */
    private fun updateSphericalHarmonicsCoefficients(coefficients: FloatArray) {

        // 在将球谐函数系数传递给着色器之前，将其预乘. 球谐因子中的常数由以下三项导出：
        // 1. 归一化球谐基函数 (y_lm)
        // 2. 朗伯漫反射 BRDF 因子（1/pi）
        // 3. 一个 <cos> 卷积，这样做是为了使结果函数输出辐射通量密度作为给定曲面法线的半球上所有入射光的总和，着色器的期望 (environmental_hdr.frag)
        // 在这可以查看更多详细的数学知识: https://google.github.io/filament/Filament.html#annex/sphericalharmonics

        kotlin.require(coefficients.size == 9 * 3) { "The given coefficients array must be of length 27 (3 components per 9 coefficients" }

        // 将每个因子应用于每个系数的每个分量
        for (i in 0 until 9 * 3) {
            sphericalHarmonicsCoefficients[i] = coefficients[i] * sphericalHarmonicFactors[i / 3]
        }
        virtualObjectShader!!.setVec3Array("u_SphericalHarmonicsCoefficients", sphericalHarmonicsCoefficients)
    }

    private fun createSession(): Boolean {
        var exception: Exception? = null
        var message: String? = null
        try {
            when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                InstallStatus.INSTALL_REQUESTED -> {
                    installRequested = true
                    return false
                }
                InstallStatus.INSTALLED -> {

                }
            }

            // ARCore 需要摄像头权限才能操作。如果我们在Android M和更高版本上还没有获得运行时权限，现在是向用户请求权限的好时机。
            if (!CameraPermissionHelper.hasCameraPermission(this)) {
                CameraPermissionHelper.requestCameraPermission(this)
                return false
            }

            // 创建 Session
            session = Session( /* context= */this)
        } catch (e: UnavailableArcoreNotInstalledException) {
            message = "Please install ARCore"
            exception = e
        } catch (e: UnavailableUserDeclinedInstallationException) {
            message = "Please install ARCore"
            exception = e
        } catch (e: UnavailableApkTooOldException) {
            message = "Please update ARCore"
            exception = e
        } catch (e: UnavailableSdkTooOldException) {
            message = "Please update this app"
            exception = e
        } catch (e: UnavailableDeviceNotCompatibleException) {
            message = "This device does not support AR"
            exception = e
        } catch (e: Exception) {
            message = "Failed to create AR session"
            exception = e
        }

        if (message != null) {
            messageSnackbarHelper.showError(this, message)
            Log.e(TAG, "Exception creating session", exception)
            return false
        }
        return true
    }

    /**
     * 使用特征设置来配置会话
     */
    private fun configureSession() {
        val config = session!!.config
        config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
        if (session!!.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
            config.depthMode = Config.DepthMode.AUTOMATIC
        } else {
            config.depthMode = Config.DepthMode.DISABLED
        }
        if (instantPlacementSettings.isInstantPlacementEnabled) {
            config.instantPlacementMode = InstantPlacementMode.LOCAL_Y_UP
        } else {
            config.instantPlacementMode = InstantPlacementMode.DISABLED
        }

//        config.focusMode = Config.FocusMode.AUTO
//        if (!setupAugmentedImageDatabase(config)) {
//            messageSnackbarHelper.showError(this, "Could not setup augmented image database")
//        }
        session!!.configure(config)
    }

//    private fun setupAugmentedImageDatabase(config: Config): Boolean {
//        var augmentedImageDatabase: AugmentedImageDatabase
//
//        // There are two ways to configure an AugmentedImageDatabase:
//        // 1. Add Bitmap to DB directly
//        // 2. Load a pre-built AugmentedImageDatabase
//        // Option 2) has
//        // * shorter setup time
//        // * doesn't require images to be packaged in apk.
//        if (useSingleImage) {
//            val augmentedImageBitmap: Bitmap = loadAugmentedImageBitmap() ?: return false
//            augmentedImageDatabase = AugmentedImageDatabase(session)
//            augmentedImageDatabase.addImage("image_name", augmentedImageBitmap)
//            // If the physical size of the image is known, you can instead use:
//            //     augmentedImageDatabase.addImage("image_name", augmentedImageBitmap, widthInMeters);
//            // This will improve the initial detection speed. ARCore will still actively estimate the
//            // physical size of the image as it is viewed from multiple viewpoints.
//        } else {
//            // This is an alternative way to initialize an AugmentedImageDatabase instance,
//            // load a pre-existing augmented image database.
//            try {
//                val inputStream : InputStream = assets.open("sample_database.imgdb")
//                augmentedImageDatabase = AugmentedImageDatabase.deserialize(session,inputStream)
//            } catch (e: IOException) {
//                Log.e(TAG, "IO exception loading augmented image database.", e)
//                return false
//            }
//        }
//        config.augmentedImageDatabase = augmentedImageDatabase
//        return true
//    }

//    private fun loadAugmentedImageBitmap(): Bitmap? {
//        try {
//            assets.open("default.jpg").use { inputStream -> return BitmapFactory.decodeStream(inputStream) }
//        } catch (e: IOException) {
//            Log.e(TAG, "IO exception loading augmented image bitmap.", e)
//        }
//        return null
//    }

    companion object {
        private val TAG: String = HelloArActivity2::class.java.name
        private const val SEARCHING_PLANE_MESSAGE = "Searching for surfaces..."
        private const val WAITING_FOR_TAP_MESSAGE = "Tap on a surface to place an object."

        // 如果要了解这些常量的含义，请参考 updateSphericalHarmonicsCoefficients() 方法的定义
        private val sphericalHarmonicFactors = floatArrayOf(
                0.282095f,
                -0.325735f,
                0.325735f,
                -0.325735f,
                0.273137f,
                -0.273137f,
                0.078848f,
                -0.273137f,
                0.136569f)

        private const val Z_NEAR = 0.1f
        private const val Z_FAR = 100f
        private const val CUBEMAP_RESOLUTION = 16
        private const val CUBEMAP_NUMBER_OF_IMPORTANCE_SAMPLES = 32

        // 设备摄像机到表面的大致距离取决于用户将东西放在哪
        // 该值会影响对象的外观比例，而即时放置点的跟踪方式是 SCREENSPACE_WITH_APPROXIMATE_DISTANCE
        // [0.2，2.0] 米范围内的值是大多数 AR 体验的好选择
        //
        // 若要使用较低的 AR 体验，则用户应将对象放置在靠近摄像机的位置
        // 若要使用较高的 AR 体验，用户更可能站着并且尝试放置对象在他们前面的地面或地板上
        private const val APPROXIMATE_DISTANCE_METERS = 2.0f
    }
}
