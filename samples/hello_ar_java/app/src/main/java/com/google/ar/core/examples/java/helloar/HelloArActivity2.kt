package com.google.ar.core.examples.java.helloar

import android.content.DialogInterface
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.ar.core.*
import com.google.ar.core.ArCoreApk.InstallStatus
import com.google.ar.core.Config.InstantPlacementMode
import com.google.ar.core.examples.java.common.helpers.*
import com.google.ar.core.examples.java.common.samplerender.*
import com.google.ar.core.examples.java.common.samplerender.arcore.BackgroundRenderer
import com.google.ar.core.examples.java.common.samplerender.arcore.PlaneRenderer
import com.google.ar.core.examples.java.common.samplerender.arcore.SpecularCubemapFilter
import com.google.ar.core.exceptions.*
import java.io.IOException
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

    // Keep track of the last point cloud rendered to avoid updating the VBO if point cloud
    // was not changed.  Do this using the timestamp since we can't compare PointCloud objects.
    private var lastPointCloudTimestamp: Long = 0

    // Virtual object (ARCore pawn)
    private var virtualObjectMesh: Mesh? = null
    private var virtualObjectShader: Shader? = null
    private val anchors = ArrayList<Anchor>()

    // Environmental HDR
    private var dfgTexture: Texture? = null
    private var cubemapFilter: SpecularCubemapFilter? = null

    // Temporary matrix allocated here to reduce number of allocations for each frame.
    private val modelMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val modelViewMatrix = FloatArray(16) // view x model
    private val modelViewProjectionMatrix = FloatArray(16) // projection x view x model
    private val sphericalHarmonicsCoefficients = FloatArray(9 * 3)
    private val viewInverseMatrix = FloatArray(16)
    private val worldLightDirection = floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f)
    private val viewLightDirection = FloatArray(4) // view x world light direction
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        surfaceView = findViewById(R.id.surfaceview)
        displayRotationHelper = DisplayRotationHelper( /*context=*/this)

        // Set up touch listener.
        tapHelper = TapHelper( /*context=*/this)
        surfaceView?.setOnTouchListener(tapHelper)

        // Set up renderer.
        render = SampleRender(surfaceView, this, assets)
        installRequested = false
        depthSettings.onCreate(this)
        instantPlacementSettings.onCreate(this)
        val settingsButton = findViewById<ImageButton>(R.id.settings_button)
        settingsButton.setOnClickListener { v ->
            val popup = PopupMenu(this@HelloArActivity2, v)
            popup.setOnMenuItemClickListener { item: MenuItem -> settingsMenuClick(item) }
            popup.inflate(R.menu.settings_menu)
            popup.show()
        }
    }

    /**
     * Menu button to launch feature specific settings.
     */
    protected fun settingsMenuClick(item: MenuItem): Boolean {
        if (item.itemId == R.id.depth_settings) {
            launchDepthSettingsMenuDialog()
            return true
        } else if (item.itemId == R.id.instant_placement_settings) {
            launchInstantPlacementSettingsMenuDialog()
            return true
        }
        return false
    }

    override fun onDestroy() {
        if (session != null) {
            // Explicitly close ARCore Session to release native resources.
            // Review the API reference for important considerations before calling close() in apps with
            // more complicated lifecycle requirements:
            // https://developers.google.com/ar/reference/java/arcore/reference/com/google/ar/core/Session#close()
            session!!.close()
            session = null
        }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (session == null) {
            var exception: Exception? = null
            var message: String? = null
            try {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = true
                        return
                    }
                    InstallStatus.INSTALLED -> {
                    }
                }

                // ARCore requires camera permissions to operate. If we did not yet obtain runtime
                // permission on Android M and above, now is a good time to ask the user for it.
                if (!CameraPermissionHelper.hasCameraPermission(this)) {
                    CameraPermissionHelper.requestCameraPermission(this)
                    return
                }

                // Create the session.
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
                return
            }
        }

        // Note that order matters - see the note in onPause(), the reverse applies here.
        try {
            configureSession()
            // To record a live camera session for later playback, call
            // `session.startRecording(recorderConfig)` at anytime. To playback a previously recorded AR
            // session instead of using the live camera feed, call
            // `session.setPlaybackDataset(playbackDatasetPath)` before calling `session.resume()`. To
            // learn more about recording and playback, see:
            // https://developers.google.com/ar/develop/java/recording-and-playback
            session!!.resume()
        } catch (e: CameraNotAvailableException) {
            messageSnackbarHelper.showError(this, "Camera not available. Try restarting the app.")
            session = null
            return
        }
        surfaceView!!.onResume()
        displayRotationHelper!!.onResume()
    }

    public override fun onPause() {
        super.onPause()
        if (session != null) {
            // Note that the order matters - GLSurfaceView is paused first so that it does not try
            // to query the session. If Session is paused before GLSurfaceView, GLSurfaceView may
            // still call session.update() and get a SessionPausedException.
            displayRotationHelper!!.onPause()
            surfaceView!!.onPause()
            session!!.pause()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
            // Use toast instead of snackbar here since the activity will exit.
            Toast.makeText(this, "Camera permission is needed to run this application", Toast.LENGTH_LONG)
                    .show()
            if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
                // Permission denied with checking "Do not ask again".
                CameraPermissionHelper.launchPermissionSettings(this)
            }
            finish()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus)
    }

    override fun onSurfaceCreated(render: SampleRender) {
        // Prepare the rendering objects. This involves reading shaders and 3D model files, so may throw
        // an IOException.
        try {
            planeRenderer = PlaneRenderer(render)
            backgroundRenderer = BackgroundRenderer(render)
            virtualSceneFramebuffer = Framebuffer(render,  /*width=*/1,  /*height=*/1)

            //镜面反射滤光片
            cubemapFilter = SpecularCubemapFilter(
                    render, CUBEMAP_RESOLUTION, CUBEMAP_NUMBER_OF_IMPORTANCE_SAMPLES)
            // Load DFG lookup table for environmental lighting
            dfgTexture = Texture(
                    render,
                    Texture.Target.TEXTURE_2D,
                    Texture.WrapMode.CLAMP_TO_EDGE,  /*useMipmaps=*/
                    false)
            // The dfg.raw file is a raw half-float texture with two channels.
            val dfgResolution = 64
            val dfgChannels = 2
            val halfFloatSize = 2
            val buffer = ByteBuffer.allocateDirect(dfgResolution * dfgResolution * dfgChannels * halfFloatSize)
            var inputStreamObj = assets.open("models/dfg.raw")
            inputStreamObj.read(buffer.array())

            //GLES30 是 openGl ES 3.0，用来添加纹理
            // SampleRender abstraction leaks here.
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

            // Point cloud
            pointCloudShader = Shader.createFromAssets(
                    render, "shaders/point_cloud.vert", "shaders/point_cloud.frag",  /*defines=*/null)
                    .setVec4(
                            "u_Color", floatArrayOf(31.0f / 255.0f, 188.0f / 255.0f, 210.0f / 255.0f, 1.0f))
                    .setFloat("u_PointSize", 5.0f)
            // four entries per vertex: X, Y, Z, confidence
            pointCloudVertexBuffer = VertexBuffer(render,  /*numberOfEntriesPerVertex=*/4,  /*entries=*/null)
            val pointCloudVertexBuffers = arrayOf(pointCloudVertexBuffer!!)
            pointCloudMesh = Mesh(
                    render, Mesh.PrimitiveMode.POINTS,  /*indexBuffer=*/null, pointCloudVertexBuffers)

            // Virtual object to render (ARCore pawn)
            val virtualObjectAlbedoTexture = Texture.createFromAsset(
                    render,
                    "models/pawn_albedo.png",
                    Texture.WrapMode.CLAMP_TO_EDGE,
                    Texture.ColorFormat.SRGB)
            val virtualObjectPbrTexture = Texture.createFromAsset(
                    render,
                    "models/pawn_roughness_metallic_ao.png",
                    Texture.WrapMode.CLAMP_TO_EDGE,
                    Texture.ColorFormat.LINEAR)
            virtualObjectMesh = Mesh.createFromAsset(render, "models/pawn.obj")
            virtualObjectShader = Shader.createFromAssets(
                    render,
                    "shaders/environmental_hdr.vert",
                    "shaders/environmental_hdr.frag",  /*defines=*/
                    object : HashMap<String?, String?>() {
                        init {
                            put(
                                    "NUMBER_OF_MIPMAP_LEVELS",
                                    Integer.toString(cubemapFilter!!.numberOfMipmapLevels))
                        }
                    })
                    .setTexture("u_AlbedoTexture", virtualObjectAlbedoTexture)
                    .setTexture("u_RoughnessMetallicAmbientOcclusionTexture", virtualObjectPbrTexture)
                    .setTexture("u_Cubemap", cubemapFilter!!.filteredCubemapTexture)
                    .setTexture("u_DfgTexture", dfgTexture)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read a required asset file", e)
            messageSnackbarHelper.showError(this, "Failed to read a required asset file: $e")
        }
    }

    override fun onSurfaceChanged(render: SampleRender, width: Int, height: Int) {
        displayRotationHelper!!.onSurfaceChanged(width, height)
        virtualSceneFramebuffer!!.resize(width, height)
    }

    override fun onDrawFrame(render: SampleRender) {
        if (session == null) {
            return
        }

        // Texture names should only be set once on a GL thread unless they change. This is done during
        // onDrawFrame rather than onSurfaceCreated since the session is not guaranteed to have been
        // initialized during the execution of onSurfaceCreated.
        if (!hasSetTextureNames) {
            session!!.setCameraTextureNames(intArrayOf(backgroundRenderer!!.cameraColorTexture.textureId))
            hasSetTextureNames = true
        }

        // -- Update per-frame state

        // Notify ARCore session that the view size changed so that the perspective matrix and
        // the video background can be properly adjusted.
        displayRotationHelper!!.updateSessionIfNeeded(session)

        // Obtain the current frame from ARSession. When the configuration is set to
        // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
        // camera framerate.
        val frame: Frame
        frame = try {
            session!!.update()
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Camera not available during onDrawFrame", e)
            messageSnackbarHelper.showError(this, "Camera not available. Try restarting the app.")
            return
        }
        val camera = frame.camera

        // Update BackgroundRenderer state to match the depth settings.
        try {
            backgroundRenderer!!.setUseDepthVisualization(
                    render, depthSettings.depthColorVisualizationEnabled())
            backgroundRenderer!!.setUseOcclusion(render, depthSettings.useDepthForOcclusion())
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read a required asset file", e)
            messageSnackbarHelper.showError(this, "Failed to read a required asset file: $e")
            return
        }
        // BackgroundRenderer.updateDisplayGeometry must be called every frame to update the coordinates
        // used to draw the background camera image.
        backgroundRenderer!!.updateDisplayGeometry(frame)
        if (camera.trackingState == TrackingState.TRACKING
                && (depthSettings.useDepthForOcclusion()
                        || depthSettings.depthColorVisualizationEnabled())) {
            try {
                frame.acquireDepthImage().use { depthImage -> backgroundRenderer!!.updateCameraDepthTexture(depthImage) }
            } catch (e: NotYetAvailableException) {
                // This normally means that depth data is not available yet. This is normal so we will not
                // spam the logcat with this.
            }
        }

        // Handle one tap per frame.
        handleTap(frame, camera)

        // Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
        trackingStateHelper.updateKeepScreenOnFlag(camera.trackingState)

        // Show a message based on whether tracking has failed, if planes are detected, and if the user
        // has placed any objects.
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

        // -- Draw background
        if (frame.timestamp != 0L) {
            // Suppress rendering if the camera did not produce the first frame yet. This is to avoid
            // drawing possible leftover data from previous sessions if the texture is reused.
            backgroundRenderer!!.drawBackground(render)
        }

        // If not tracking, don't draw 3D objects.
        if (camera.trackingState == TrackingState.PAUSED) {
            return
        }

        // -- Draw non-occluded virtual objects (planes, point cloud)

        // Get projection matrix.
        camera.getProjectionMatrix(projectionMatrix, 0, Z_NEAR, Z_FAR)

        // Get camera matrix and draw.
        camera.getViewMatrix(viewMatrix, 0)
        frame.acquirePointCloud().use { pointCloud ->
            if (pointCloud.getTimestamp() > lastPointCloudTimestamp) {
                pointCloudVertexBuffer!!.set(pointCloud.getPoints())
                lastPointCloudTimestamp = pointCloud.getTimestamp()
            }
            Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
            pointCloudShader!!.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)
            render.draw(pointCloudMesh, pointCloudShader)
        }

        // Visualize planes.
        planeRenderer!!.drawPlanes(
                render,
                session!!.getAllTrackables(Plane::class.java),
                camera.displayOrientedPose,
                projectionMatrix)

        // -- Draw occluded virtual objects

        // Update lighting parameters in the shader
        updateLightEstimation(frame.lightEstimate, viewMatrix)

        // Visualize anchors created by touch.
        render.clear(virtualSceneFramebuffer, 0f, 0f, 0f, 0f)
        for (anchor in anchors) {
            if (anchor.trackingState != TrackingState.TRACKING) {
                continue
            }

            // Get the current pose of an Anchor in world space. The Anchor pose is updated
            // during calls to session.update() as ARCore refines its estimate of the world.
            anchor.pose.toMatrix(modelMatrix, 0)

            // Calculate model/view/projection matrices
            Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)
            Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)

            // Update shader properties and draw
            virtualObjectShader!!.setMat4("u_ModelView", modelViewMatrix)
            virtualObjectShader!!.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)
            render.draw(virtualObjectMesh, virtualObjectShader, virtualSceneFramebuffer)
        }

        // Compose the virtual scene with the background.
        backgroundRenderer!!.drawVirtualScene(render, virtualSceneFramebuffer, Z_NEAR, Z_FAR)
    }

    // Handle only one tap per frame, as taps are usually low frequency compared to frame rate.
    private fun handleTap(frame: Frame, camera: Camera) {
        val tap = tapHelper!!.poll()
        if (tap != null && camera.trackingState == TrackingState.TRACKING) {
            val hitResultList: List<HitResult>
            hitResultList = if (instantPlacementSettings.isInstantPlacementEnabled) {
                frame.hitTestInstantPlacement(tap.x, tap.y, APPROXIMATE_DISTANCE_METERS)
            } else {
                frame.hitTest(tap)
            }
            for (hit in hitResultList) {
                // If any plane, Oriented Point, or Instant Placement Point was hit, create an anchor.
                val trackable = hit.trackable
                // If a plane was hit, check that it was hit inside the plane polygon.
                if ((trackable is Plane
                                && trackable.isPoseInPolygon(hit.hitPose)
                                && PlaneRenderer.calculateDistanceToPlane(hit.hitPose, camera.pose) > 0)
                        || (trackable is Point
                                && trackable.orientationMode
                                == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL)
                        || trackable is InstantPlacementPoint) {
                    // Cap the number of objects created. This avoids overloading both the
                    // rendering system and ARCore.
                    if (anchors.size >= 20) {
                        anchors[0].detach()
                        anchors.removeAt(0)
                    }

                    // Adding an Anchor tells ARCore that it should track this position in
                    // space. This anchor is created on the Plane to place the 3D model
                    // in the correct position relative both to the world and to the plane.
                    anchors.add(hit.createAnchor())
                    // For devices that support the Depth API, shows a dialog to suggest enabling
                    // depth-based occlusion. This dialog needs to be spawned on the UI thread.
                    runOnUiThread { showOcclusionDialogIfNeeded() }

                    // Hits are sorted by depth. Consider only closest hit on a plane, Oriented Point, or
                    // Instant Placement Point.
                    break
                }
            }
        }
    }

    /**
     * Shows a pop-up dialog on the first call, determining whether the user wants to enable
     * depth-based occlusion. The result of this dialog can be retrieved with useDepthForOcclusion().
     */
    private fun showOcclusionDialogIfNeeded() {
        val isDepthSupported = session!!.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
        if (!depthSettings.shouldShowDepthEnableDialog() || !isDepthSupported) {
            return  // Don't need to show dialog.
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

    private fun launchInstantPlacementSettingsMenuDialog() {
        resetSettingsMenuDialogCheckboxes()
        val resources = resources
        AlertDialog.Builder(this)
                .setTitle(R.string.options_title_instant_placement)
                .setMultiChoiceItems(
                        resources.getStringArray(R.array.instant_placement_options_array),
                        instantPlacementSettingsMenuDialogCheckboxes
                ) { dialog: DialogInterface?, which: Int, isChecked: Boolean -> instantPlacementSettingsMenuDialogCheckboxes[which] = isChecked }
                .setPositiveButton(
                        R.string.done
                ) { dialogInterface: DialogInterface?, which: Int -> applySettingsMenuDialogCheckboxes() }
                .setNegativeButton(
                        android.R.string.cancel
                ) { dialog: DialogInterface?, which: Int -> resetSettingsMenuDialogCheckboxes() }
                .show()
    }

    /**
     * Shows checkboxes to the user to facilitate toggling of depth-based effects.
     */
    private fun launchDepthSettingsMenuDialog() {
        // Retrieves the current settings to show in the checkboxes.
        resetSettingsMenuDialogCheckboxes()

        // Shows the dialog to the user.
        val resources = resources
        if (session!!.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
            // With depth support, the user can select visualization options.
            AlertDialog.Builder(this)
                    .setTitle(R.string.options_title_with_depth)
                    .setMultiChoiceItems(
                            resources.getStringArray(R.array.depth_options_array),
                            depthSettingsMenuDialogCheckboxes
                    ) { dialog: DialogInterface?, which: Int, isChecked: Boolean -> depthSettingsMenuDialogCheckboxes[which] = isChecked }
                    .setPositiveButton(
                            R.string.done
                    ) { dialogInterface: DialogInterface?, which: Int -> applySettingsMenuDialogCheckboxes() }
                    .setNegativeButton(
                            android.R.string.cancel
                    ) { dialog: DialogInterface?, which: Int -> resetSettingsMenuDialogCheckboxes() }
                    .show()
        } else {
            // Without depth support, no settings are available.
            AlertDialog.Builder(this)
                    .setTitle(R.string.options_title_without_depth)
                    .setPositiveButton(
                            R.string.done
                    ) { dialogInterface: DialogInterface?, which: Int -> applySettingsMenuDialogCheckboxes() }
                    .show()
        }
    }

    private fun applySettingsMenuDialogCheckboxes() {
        depthSettings.setUseDepthForOcclusion(depthSettingsMenuDialogCheckboxes[0])
        depthSettings.setDepthColorVisualizationEnabled(depthSettingsMenuDialogCheckboxes[1])
        instantPlacementSettings.isInstantPlacementEnabled = instantPlacementSettingsMenuDialogCheckboxes[0]
        configureSession()
    }

    private fun resetSettingsMenuDialogCheckboxes() {
        depthSettingsMenuDialogCheckboxes[0] = depthSettings.useDepthForOcclusion()
        depthSettingsMenuDialogCheckboxes[1] = depthSettings.depthColorVisualizationEnabled()
        instantPlacementSettingsMenuDialogCheckboxes[0] = instantPlacementSettings.isInstantPlacementEnabled
    }

    /**
     * Checks if we detected at least one plane.
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
     * Update state based on the current frame's light estimation.
     */
    private fun updateLightEstimation(lightEstimate: LightEstimate, viewMatrix: FloatArray) {
        if (lightEstimate.state != LightEstimate.State.VALID) {
            virtualObjectShader!!.setBool("u_LightEstimateIsValid", false)
            return
        }
        virtualObjectShader!!.setBool("u_LightEstimateIsValid", true)
        Matrix.invertM(viewInverseMatrix, 0, viewMatrix, 0)
        virtualObjectShader!!.setMat4("u_ViewInverse", viewInverseMatrix)
        updateMainLight(
                lightEstimate.environmentalHdrMainLightDirection,
                lightEstimate.environmentalHdrMainLightIntensity,
                viewMatrix)
        updateSphericalHarmonicsCoefficients(
                lightEstimate.environmentalHdrAmbientSphericalHarmonics)
        cubemapFilter!!.update(lightEstimate.acquireEnvironmentalHdrCubeMap())
    }

    private fun updateMainLight(direction: FloatArray, intensity: FloatArray, viewMatrix: FloatArray) {
        // We need the direction in a vec4 with 0.0 as the final component to transform it to view space
        worldLightDirection[0] = direction[0]
        worldLightDirection[1] = direction[1]
        worldLightDirection[2] = direction[2]
        Matrix.multiplyMV(viewLightDirection, 0, viewMatrix, 0, worldLightDirection, 0)
        virtualObjectShader!!.setVec4("u_ViewLightDirection", viewLightDirection)
        virtualObjectShader!!.setVec3("u_LightIntensity", intensity)
    }

    private fun updateSphericalHarmonicsCoefficients(coefficients: FloatArray) {
        // Pre-multiply the spherical harmonics coefficients before passing them to the shader. The
        // constants in sphericalHarmonicFactors were derived from three terms:
        //
        // 1. The normalized spherical harmonics basis functions (y_lm)
        //
        // 2. The lambertian diffuse BRDF factor (1/pi)
        //
        // 3. A <cos> convolution. This is done to so that the resulting function outputs the irradiance
        // of all incoming light over a hemisphere for a given surface normal, which is what the shader
        // (environmental_hdr.frag) expects.
        //
        // You can read more details about the math here:
        // https://google.github.io/filament/Filament.html#annex/sphericalharmonics
        kotlin.require(coefficients.size == 9 * 3) { "The given coefficients array must be of length 27 (3 components per 9 coefficients" }

        // Apply each factor to every component of each coefficient
        for (i in 0 until 9 * 3) {
            sphericalHarmonicsCoefficients[i] = coefficients[i] * sphericalHarmonicFactors[i / 3]
        }
        virtualObjectShader!!.setVec3Array(
                "u_SphericalHarmonicsCoefficients", sphericalHarmonicsCoefficients)
    }

    /**
     * Configures the session with feature settings.
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
        session!!.configure(config)
    }

    companion object {
        private val TAG: String = HelloArActivity2::class.java.name
        private const val SEARCHING_PLANE_MESSAGE = "Searching for surfaces..."
        private const val WAITING_FOR_TAP_MESSAGE = "Tap on a surface to place an object."

        // See the definition of updateSphericalHarmonicsCoefficients for an explanation of these
        // constants.
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

        // Assumed distance from the device camera to the surface on which user will try to place objects.
        // This value affects the apparent scale of objects while the tracking method of the
        // Instant Placement point is SCREENSPACE_WITH_APPROXIMATE_DISTANCE.
        // Values in the [0.2, 2.0] meter range are a good choice for most AR experiences. Use lower
        // values for AR experiences where users are expected to place objects on surfaces close to the
        // camera. Use larger values for experiences where the user will likely be standing and trying to
        // place an object on the ground or floor in front of them.
        private const val APPROXIMATE_DISTANCE_METERS = 2.0f
    }
}
