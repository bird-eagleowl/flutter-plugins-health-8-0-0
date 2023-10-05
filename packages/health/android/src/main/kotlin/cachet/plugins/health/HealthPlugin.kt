package cachet.plugins.health

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.util.Log
import androidx.annotation.NonNull
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.*
import android.health.connect.HealthConnectManager
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.fitness.Fitness
import com.google.android.gms.fitness.FitnessActivities
import com.google.android.gms.fitness.FitnessOptions
import com.google.android.gms.fitness.data.*
import com.google.android.gms.fitness.request.DataDeleteRequest
import com.google.android.gms.fitness.request.DataReadRequest
import com.google.android.gms.fitness.request.SessionInsertRequest
import com.google.android.gms.fitness.request.SessionReadRequest
import com.google.android.gms.fitness.result.DataReadResponse
import com.google.android.gms.fitness.result.SessionReadResponse
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.OnSuccessListener
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry.ActivityResultListener
import io.flutter.plugin.common.PluginRegistry.Registrar
import kotlinx.coroutines.*
import java.time.*
import java.time.temporal.ChronoUnit
import java.util.*
import java.util.concurrent.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.ActivityResultLauncher


const val HEALTH_CONNECT_RESULT_CODE = 16969
const val CHANNEL_NAME = "flutter_health"
const val MMOLL_2_MGDL = 18.0 // 1 mmoll= 18 mgdl


class HealthPlugin(private var channel: MethodChannel? = null) :
    MethodCallHandler,
    ActivityResultListener,
    Result,
    ActivityAware,
    FlutterPlugin {
    private var mResult: Result? = null
    private var handler: Handler? = null
    private var activity: Activity? = null
    private var context: Context? = null
    private var threadPoolExecutor: ExecutorService? = null
    private var useHealthConnectIfAvailable: Boolean = false
    private lateinit var healthConnectClient: HealthConnectClient
    private lateinit var scope: CoroutineScope

    private var BODY_FAT_PERCENTAGE = "BODY_FAT_PERCENTAGE"
    private var HEIGHT = "HEIGHT"
    private var WEIGHT = "WEIGHT"
    private var STEPS = "STEPS"
    private var ACTIVE_ENERGY_BURNED = "ACTIVE_ENERGY_BURNED"
    private var HEART_RATE = "HEART_RATE"


    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        Log.i("test1", "onAttachedToEngine")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, CHANNEL_NAME)
        channel?.setMethodCallHandler(this)
        context = flutterPluginBinding.applicationContext
        threadPoolExecutor = Executors.newFixedThreadPool(4)
        checkAvailability()
        if (healthConnectAvailable) {
            healthConnectClient =
                HealthConnectClient.getOrCreate(flutterPluginBinding.applicationContext)
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        Log.i("test1", "onDetachedFromEngine")
        channel = null
        activity = null
        threadPoolExecutor!!.shutdown()
        threadPoolExecutor = null
    }

    companion object {
        @Suppress("unused")
        @JvmStatic
        fun registerWith(registrar: Registrar) {
            val channel = MethodChannel(registrar.messenger(), CHANNEL_NAME)
            val plugin = HealthPlugin(channel)
            registrar.addActivityResultListener(plugin)
            channel.setMethodCallHandler(plugin)
        }
    }

    override fun success(p0: Any?) {
        handler?.post { mResult?.success(p0) }
    }

    override fun notImplemented() {
        handler?.post { mResult?.notImplemented() }
    }

    override fun error(
        errorCode: String,
        errorMessage: String?,
        errorDetails: Any?,
    ) {
        handler?.post { mResult?.error(errorCode, errorMessage, errorDetails) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        Log.i("test1", "onActivityResult")

        if (requestCode == HEALTH_CONNECT_RESULT_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                if (data != null) {
                    if(data.extras?.containsKey("request_blocked") == true) {
                        Log.i("FLUTTER_HEALTH", "Access Denied (to Health Connect) due to too many requests!")
                        mResult?.success(false)
                        return false
                    }
                }
                Log.i("FLUTTER_HEALTH", "Access Granted (to Health Connect)!")
                mResult?.success(true)
            } else if (resultCode == Activity.RESULT_CANCELED) {
                Log.i("FLUTTER_HEALTH", "Access Denied (to Health Connect)!")
                mResult?.success(false)
            }
        }
        return false
    }



    // / Extracts the (numeric) value from a Health Data Point
    private fun getHealthDataValue(dataPoint: DataPoint, field: Field): Any {
        Log.i("test1", "getHealthDataValue")
        val value = dataPoint.getValue(field)
        // Conversion is needed because glucose is stored as mmoll in Google Fit;
        // while mgdl is used for glucose in this plugin.
        val isGlucose = field == HealthFields.FIELD_BLOOD_GLUCOSE_LEVEL
        return when (value.format) {
            Field.FORMAT_FLOAT -> if (!isGlucose) value.asFloat() else value.asFloat() * MMOLL_2_MGDL
            Field.FORMAT_INT32 -> value.asInt()
            Field.FORMAT_STRING -> value.asString()
            else -> Log.e("Unsupported format:", value.format.toString())
        }
    }




    private fun writeData(call: MethodCall, result: Result) {
        Log.i("test1", "writeData")
        if (useHealthConnectIfAvailable && healthConnectAvailable) {
            writeHCData(call, result)
            return
        }
        if (context == null) {
            result.success(false)
            return
        }

    }

    /**
     * Get all datapoints of the DataType within the given time range
     */
    private fun getData(call: MethodCall, result: Result) {
        Log.i("test1", "getData")
        if (useHealthConnectIfAvailable && healthConnectAvailable) {
            getHCData(call, result)
            return
        }

        if (context == null) {
            result.success(null)
            return
        }
    }

    private fun dataHandler(dataType: DataType, field: Field, result: Result) =
        OnSuccessListener { response: DataReadResponse ->
            // / Fetch all data points for the specified DataType
            val dataSet = response.getDataSet(dataType)
            // / For each data point, extract the contents and send them to Flutter, along with date and unit.
            val healthData = dataSet.dataPoints.mapIndexed { _, dataPoint ->
                return@mapIndexed hashMapOf(
                    "value" to getHealthDataValue(dataPoint, field),
                    "date_from" to dataPoint.getStartTime(TimeUnit.MILLISECONDS),
                    "date_to" to dataPoint.getEndTime(TimeUnit.MILLISECONDS),
                    "source_name" to (
                            dataPoint.originalDataSource.appPackageName
                                ?: (
                                        dataPoint.originalDataSource.device?.model
                                            ?: ""
                                        )
                            ),
                    "source_id" to dataPoint.originalDataSource.streamIdentifier,
                )
            }
            Handler(context!!.mainLooper).run { result.success(healthData) }
        }

    private fun errHandler(result: Result, addMessage: String) = OnFailureListener { exception ->
        Handler(context!!.mainLooper).run { result.success(null) }
        Log.w("FLUTTER_HEALTH::ERROR", addMessage)
        Log.w("FLUTTER_HEALTH::ERROR", exception.message ?: "unknown error")
        Log.w("FLUTTER_HEALTH::ERROR", exception.stackTrace.toString())
    }

    private fun checkSdk(call: MethodCall, result: Result) {
        Log.i("test1", "checkSdk")
        result.success(HealthConnectClient.getSdkStatus(context!!))
    }

    private fun hasPermissions(call: MethodCall, result: Result) {
        Log.i("test1", "hasPermissions")

        if (useHealthConnectIfAvailable && healthConnectAvailable) {
            hasPermissionsHC(call, result)
            return
        }
        if (context == null) {
            result.success(false)
            return
        }

    }

    /**
     * Requests authorization for the HealthDataTypes
     * with the the READ or READ_WRITE permission type.
     */
    private fun requestAuthorization(call: MethodCall, result: Result) {
        Log.i("test1", "requestAuthorization")
        if (context == null) {
            result.success(false)
            return
        }
        mResult = result

        if (useHealthConnectIfAvailable && healthConnectAvailable) {
            requestAuthorizationHC(call, result)
            return
        }
    }

    /**
     * Revokes access to Google Fit using the `disableFit`-method.
     *
     * Note: Using the `revokeAccess` creates a bug on android
     * when trying to reapply for permissions afterwards, hence
     * `disableFit` was used.
     */
    private fun revokePermissions(call: MethodCall, result: Result) {
        Log.i("test1", "revokePermissions")
        if (useHealthConnectIfAvailable && healthConnectAvailable) {
            result.notImplemented()
            return
        }
        if (context == null) {
            result.success(false)
            return
        }
    }

    private fun getTotalStepsInInterval(call: MethodCall, result: Result) {
        Log.i("test1", "getTotalStepsInInterval")
        val start = call.argument<Long>("startTime")!!
        val end = call.argument<Long>("endTime")!!

        if (useHealthConnectIfAvailable && healthConnectAvailable) {
            getStepsHealthConnect(start, end, result)
            return
        }
    }

    private fun getStepsHealthConnect(start: Long, end: Long, result: Result) = scope.launch {
        Log.i("test1", "getStepsHealthConnect")
        try {
            val startInstant = Instant.ofEpochMilli(start)
            val endInstant = Instant.ofEpochMilli(end)
            val response = healthConnectClient.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(startInstant, endInstant),
                ),
            )
            // The result may be null if no data is available in the time range.
            val stepsInInterval = response[StepsRecord.COUNT_TOTAL] ?: 0L
            Log.i("FLUTTER_HEALTH::SUCCESS", "returning $stepsInInterval steps")
            result.success(stepsInInterval)
        } catch (e: Exception) {
            Log.i("FLUTTER_HEALTH::ERROR", "unable to return steps")
            result.success(null)
        }
    }


    /**
     *  Handle calls from the MethodChannel
     */
    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "useHealthConnectIfAvailable" -> useHealthConnectIfAvailable(call, result)
            "hasPermissions" -> hasPermissions(call, result)
            "requestAuthorization" -> requestAuthorization(call, result)
            "revokePermissions" -> revokePermissions(call, result)
            "getData" -> getData(call, result)
            "writeData" -> writeData(call, result)
            "checkSdk" -> checkSdk(call, result)
            else -> result.notImplemented()
        }
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        Log.i("test1", "onAttachedToActivity")
        if (channel == null) {
            return
        }
        binding.addActivityResultListener(this)
        activity = binding.activity
    }

    override fun onDetachedFromActivityForConfigChanges() {
        Log.i("test1", "onDetachedFromActivityForConfigChanges")
        onDetachedFromActivity()
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        Log.i("test1", "onReattachedToActivityForConfigChanges")
        onAttachedToActivity(binding)
    }

    override fun onDetachedFromActivity() {
        Log.i("test1", "onDetachedFromActivity")
        if (channel == null) {
            Log.i("test1", "onDetachedFromActivity2")
            return
        }
        Log.i("test1", "onDetachedFromActivity3")
        activity = null
    }

    /**
     * HEALTH CONNECT BELOW
     */
    var healthConnectAvailable = false
    var healthConnectStatus = HealthConnectClient.SDK_UNAVAILABLE

    fun checkAvailability() {
        healthConnectStatus = HealthConnectClient.getSdkStatus(context!!)
        if (healthConnectStatus == HealthConnectClient.SDK_UNAVAILABLE) {

            return // SDK 사용 불가능한 경우 조기 반환
        }
        if (healthConnectStatus == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) {
            return
        }
        healthConnectAvailable = true

        Log.i("test1", "checkAvailability")
    }

    fun useHealthConnectIfAvailable(call: MethodCall, result: Result) {
        Log.i("test1", "useHealthConnectIfAvailable")
        useHealthConnectIfAvailable = true
        result.success(null)
    }

    private fun hasPermissionsHC(call: MethodCall, result: Result) {
        Log.i("test1", "hasPermissionsHC")
        val args = call.arguments as HashMap<*, *>
        val types = (args["types"] as? ArrayList<*>)?.filterIsInstance<String>()!!
        val permissions = (args["permissions"] as? ArrayList<*>)?.filterIsInstance<Int>()!!

        var permList = mutableListOf<String>()
        for ((i, typeKey) in types.withIndex()) {
            val access = permissions[i]!!
            val dataType = MapToHCType[typeKey]!!
            if (access == 0) {
                permList.add(
                    HealthPermission.getReadPermission(dataType),
                )
            } else {
                permList.addAll(
                    listOf(
                        HealthPermission.getReadPermission(dataType),
                        HealthPermission.getWritePermission(dataType),
                    ),
                )
            }

        }
        scope.launch {
            result.success(
                healthConnectClient.permissionController.getGrantedPermissions()
                    .containsAll(permList),
            )
        }
    }

    private fun requestAuthorizationHC(call: MethodCall, result: Result) {
        Log.i("test1", "requestAuthorizationHC")
        val args = call.arguments as HashMap<*, *>
        val types = (args["types"] as? ArrayList<*>)?.filterIsInstance<String>()!!
        val permissions = (args["permissions"] as? ArrayList<*>)?.filterIsInstance<Int>()!!

        var permList = mutableListOf<String>()
        for ((i, typeKey) in types.withIndex()) {
            val access = permissions[i]!!
            val dataType = MapToHCType[typeKey]!!
            if (access == 0) {
                permList.add(
                    HealthPermission.getReadPermission(dataType),
                )
            } else {
                permList.addAll(
                    listOf(
                        HealthPermission.getReadPermission(dataType),
                        HealthPermission.getWritePermission(dataType),
                    ),
                )
            }

        }
         val contract = PermissionController.createRequestPermissionResultContract()
        val intent = contract.createIntent(activity!!, permList.toSet())
         activity!!.startActivityForResult(intent, HEALTH_CONNECT_RESULT_CODE)
    }

    fun getHCData(call: MethodCall, result: Result) {
        Log.i("test1", "getHCData")
        val dataType = call.argument<String>("dataTypeKey")!!
        val startTime = Instant.ofEpochMilli(call.argument<Long>("startTime")!!)
        val endTime = Instant.ofEpochMilli(call.argument<Long>("endTime")!!)
        val healthConnectData = mutableListOf<Map<String, Any?>>()
        scope.launch {
            MapToHCType[dataType]?.let { classType ->
                val request = ReadRecordsRequest(
                    recordType = classType,
                    timeRangeFilter = TimeRangeFilter.between(startTime, endTime),
                )
                val response = healthConnectClient.readRecords(request)

                for (rec in response.records) {
                    healthConnectData.addAll(convertRecord(rec, dataType))
                }

            }
            Handler(context!!.mainLooper).run { result.success(healthConnectData) }
        }
    }

    // TODO: Find alternative to SOURCE_ID or make it nullable?
    fun convertRecord(record: Any, dataType: String): List<Map<String, Any>> {
        val metadata = (record as Record).metadata
        when (record) {
            is WeightRecord -> return listOf(
                mapOf<String, Any>(
                    "value" to record.weight.inKilograms,
                    "date_from" to record.time.toEpochMilli(),
                    "date_to" to record.time.toEpochMilli(),
                    "source_id" to "",
                    "source_name" to metadata.dataOrigin.packageName,
                ),
            )
            is HeightRecord -> return listOf(
                mapOf<String, Any>(
                    "value" to record.height.inMeters,
                    "date_from" to record.time.toEpochMilli(),
                    "date_to" to record.time.toEpochMilli(),
                    "source_id" to "",
                    "source_name" to metadata.dataOrigin.packageName,
                ),
            )
            is BodyFatRecord -> return listOf(
                mapOf<String, Any>(
                    "value" to record.percentage.value,
                    "date_from" to record.time.toEpochMilli(),
                    "date_to" to record.time.toEpochMilli(),
                    "source_id" to "",
                    "source_name" to metadata.dataOrigin.packageName,
                ),
            )
            is StepsRecord -> return listOf(
                mapOf<String, Any>(
                    "value" to record.count,
                    "date_from" to record.startTime.toEpochMilli(),
                    "date_to" to record.endTime.toEpochMilli(),
                    "source_id" to "",
                    "source_name" to metadata.dataOrigin.packageName,
                ),
            )
            is ActiveCaloriesBurnedRecord -> return listOf(
                mapOf<String, Any>(
                    "value" to record.energy.inKilocalories,
                    "date_from" to record.startTime.toEpochMilli(),
                    "date_to" to record.endTime.toEpochMilli(),
                    "source_id" to "",
                    "source_name" to metadata.dataOrigin.packageName,
                ),
            )
            is HeartRateRecord -> return record.samples.map {
                mapOf<String, Any>(
                    "value" to it.beatsPerMinute,
                    "date_from" to it.time.toEpochMilli(),
                    "date_to" to it.time.toEpochMilli(),
                    "source_id" to "",
                    "source_name" to metadata.dataOrigin.packageName,
                )
            }
            else -> throw IllegalArgumentException("Health data type not supported") // TODO: Exception or error?
        }
    }

    fun writeHCData(call: MethodCall, result: Result) {
        Log.i("test1", "writeHCData")
        val type = call.argument<String>("dataTypeKey")!!
        val startTime = call.argument<Long>("startTime")!!
        val endTime = call.argument<Long>("endTime")!!
        val value = call.argument<Double>("value")!!
        val record = when (type) {
            BODY_FAT_PERCENTAGE -> BodyFatRecord(
                time = Instant.ofEpochMilli(startTime),
                percentage = Percentage(value),
                zoneOffset = null,
            )
            HEIGHT -> HeightRecord(
                time = Instant.ofEpochMilli(startTime),
                height = Length.meters(value),
                zoneOffset = null,
            )
            WEIGHT -> WeightRecord(
                time = Instant.ofEpochMilli(startTime),
                weight = Mass.kilograms(value),
                zoneOffset = null,
            )
            STEPS -> StepsRecord(
                startTime = Instant.ofEpochMilli(startTime),
                endTime = Instant.ofEpochMilli(endTime),
                count = value.toLong(),
                startZoneOffset = null,
                endZoneOffset = null,
            )
            ACTIVE_ENERGY_BURNED -> ActiveCaloriesBurnedRecord(
                startTime = Instant.ofEpochMilli(startTime),
                endTime = Instant.ofEpochMilli(endTime),
                energy = Energy.kilocalories(value),
                startZoneOffset = null,
                endZoneOffset = null,
            )
            HEART_RATE -> HeartRateRecord(
                startTime = Instant.ofEpochMilli(startTime),
                endTime = Instant.ofEpochMilli(endTime),
                samples = listOf<HeartRateRecord.Sample>(
                    HeartRateRecord.Sample(
                        time = Instant.ofEpochMilli(startTime),
                        beatsPerMinute = value.toLong(),
                    ),
                ),
                startZoneOffset = null,
                endZoneOffset = null,
            )
            else -> throw IllegalArgumentException("The type $type was not supported by the Health plugin or you must use another API ")
        }
        scope.launch {
            try {
                healthConnectClient.insertRecords(listOf(record))
                result.success(true)
            } catch (e: Exception) {
                result.success(false)
            }
        }
    }



    val MapToHCType = hashMapOf(
        BODY_FAT_PERCENTAGE to BodyFatRecord::class,
        HEIGHT to HeightRecord::class,
        WEIGHT to WeightRecord::class,
        STEPS to StepsRecord::class,
        ACTIVE_ENERGY_BURNED to ActiveCaloriesBurnedRecord::class,
        HEART_RATE to HeartRateRecord::class,

        )
}
