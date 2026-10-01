package com.omg.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

// ----------------------------------------------------------------------------
// 1. DATA MODELS
// ----------------------------------------------------------------------------

data class ReceiptItem(
    @SerializedName("name") val name: String = "",
    @SerializedName("normalizedName") val normalizedName: String? = null,
    @SerializedName("quantity") val quantity: Double = 1.0,
    @SerializedName("unitPrice") val unitPrice: Double? = null,
    @SerializedName("totalPrice") val totalPrice: Double = 0.0
)

data class ReceiptResponse(
    @SerializedName("storeName") val storeName: String? = null,
    @SerializedName("receiptId") val receiptId: String? = null,
    @SerializedName("date") val date: String? = null,
    @SerializedName("items") val items: List<ReceiptItem> = emptyList(),
    @SerializedName("subtotal") val subtotal: Double? = null,
    @SerializedName("discount") val discount: Double? = null,
    @SerializedName("tax") val tax: Double? = null,
    @SerializedName("totalAmount") val totalAmount: Double = 0.0
)

data class GeminiAnalysisResult(
    @SerializedName("statusCode") val statusCode: Int = 200,
    @SerializedName("errorMessage") val errorMessage: String? = null,
    @SerializedName("receipt") val receipt: ReceiptResponse? = null
)

sealed class ScannerUiState {
    object Idle : ScannerUiState()
    object Loading : ScannerUiState()
    data class Success(val receipt: ReceiptResponse) : ScannerUiState()
    // Lỗi gian lận (statusCode 400) để kích hoạt popup
    data class FraudError(val message: String, val statusCode: Int = 400) : ScannerUiState()
    // Lỗi hệ thống thông thường (mạng, ngoại lệ, ...)
    data class SystemError(val message: String) : ScannerUiState()
}

// ----------------------------------------------------------------------------
// 2. VIEWMODEL XỬ LÝ GEMINI MULTI-IMAGE & CHỐNG GIAN LẬN
// ----------------------------------------------------------------------------

class ReceiptScannerViewModel : ViewModel() {

    private val geminiApiKey = "AQ.Ab8RN6LJSPKM5En5SZ3YiZ3U8HLt8Hr0kb717bqIULqALCwqpQ".trim()
    private val modelName = "gemini-3.6-flash".trim()

    private val _uiState = MutableStateFlow<ScannerUiState>(ScannerUiState.Idle)
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun resetState() {
        _uiState.value = ScannerUiState.Idle
    }

    fun processReceiptImages(context: Context, imageUris: List<Uri>) {
        if (imageUris.isEmpty()) return

        viewModelScope.launch {
            _uiState.value = ScannerUiState.Loading

            try {
                // Đọc và tối ưu kích thước toàn bộ các ảnh chụp
                val base64Images = withContext(Dispatchers.IO) {
                    imageUris.mapNotNull { uri ->
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            val originalBitmap = BitmapFactory.decodeStream(stream)
                            if (originalBitmap != null) {
                                val resizedBitmap = resizeBitmapPreservingAspect(originalBitmap, 1280)
                                val outputStream = ByteArrayOutputStream()
                                resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
                                Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
                            } else null
                        }
                    }
                }

                if (base64Images.isEmpty()) {
                    _uiState.value = ScannerUiState.SystemError("Không thể đọc tệp hình ảnh đã chụp")
                    return@launch
                }

                val resultJson = callGeminiVisionApi(base64Images)
                val analysisResult = Gson().fromJson(resultJson, GeminiAnalysisResult::class.java)

                // Kiểm tra mã trạng thái trả về từ Gemini
                if (analysisResult.statusCode == 400) {
                    val message = analysisResult.errorMessage ?: "Phát hiện dấu hiệu gian lận hoặc ảnh không hợp lệ"
                    _uiState.value = ScannerUiState.FraudError(message = message, statusCode = 400)
                } else if (analysisResult.statusCode != 200) {
                    val message = analysisResult.errorMessage ?: "Lỗi xử lý hóa đơn"
                    _uiState.value = ScannerUiState.SystemError(message)
                } else if (analysisResult.receipt != null) {
                    _uiState.value = ScannerUiState.Success(analysisResult.receipt)
                } else {
                    _uiState.value = ScannerUiState.SystemError("Không tìm thấy nội dung chi tiết hóa đơn")
                }

            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.value = ScannerUiState.SystemError("Lỗi hệ thống: ${e.localizedMessage ?: "Không xác định"}")
            }
        }
    }

    private suspend fun callGeminiVisionApi(base64Images: List<String>): String = withContext(Dispatchers.IO) {
        val prompt = """
    Bạn là hệ thống kiểm định an ninh và bóc tách dữ liệu hóa đơn tự động có tính năng chống gian lận đa tầng.
    Các hình ảnh gửi kèm có thể là nhiều trang hoặc góc chụp khác nhau của hóa đơn.

    QUY TẮC BẮT BUỘC:
    1. DÙ CÓ LỖI HAY HỢP LỆ, LUÔN LUÔN TRẢ VỀ ĐÚNG ĐỊNH DẠNG JSON THEO SCHEMA ĐÃ CẤU HÌNH. TUYỆT ĐỐI KHÔNG TRẢ VỀ MARKDOWN HOẶC TEXT TỰ DO.
    
    2. QUY TẮC PHÁT HIỆN VÀ CHẶN GIAN LẬN (BẮT BUỘC TRẢ VỀ statusCode = 400 VÀ "receipt": null NẾU VI PHẠM BẤT KỲ ĐIỀU NÀO):
       
       a. Khác biệt thông tin định danh giữa các ảnh:
          - Nếu các ảnh chứa 2 mã hóa đơn/số phiếu/bill ID khác nhau:
            -> "errorMessage": "Phát hiện 2 mã bill khác nhau"
          - Nếu các ảnh thuộc về 2 cửa hàng, thương hiệu hoặc chi nhánh khác nhau:
            -> "errorMessage": "Phát hiện hóa đơn từ các cửa hàng khác nhau"
          - Nếu ngày, tháng, năm hoặc giờ in trên các ảnh bị lệch nhau rõ ràng:
            -> "errorMessage": "Phát hiện các hóa đơn khác ngày lập"

       b. Hóa đơn không hợp lệ, hóa đơn nháp hoặc từ khóa cấm:
          - Xuất hiện từ khóa tạm tính, chưa thanh toán ("TẠM TÍNH", "PROFORMA", "PHIẾU BÁO GIÁ", "ESTIMATE", "ORDER SLIP"):
            -> "errorMessage": "Hóa đơn tạm tính hoặc phiếu báo giá chưa thanh toán"
          - Xuất hiện dấu hiệu đã hủy, hoàn trả ("HỦY", "VOID", "CANCELLED", "HOÀN TIỀN", "REFUND", "CREDIT NOTE"):
            -> "errorMessage": "Hóa đơn đã bị hủy hoặc là phiếu hoàn tiền"
          - Xuất hiện dấu hiệu bản sao, in lại của nhân viên ("BẢN IN LẠI", "REPRINT", "LIÊN 2", "LIÊN LƯU NỘI BỘ", "MERCHANT COPY", "DUPLICATE"):
            -> "errorMessage": "Chứng từ là bản in lại hoặc liên lưu nội bộ của thu ngân"
          - Không phát hiện được mã hoá đơn:
            -> "errorMessage": "Không tìm thấy mã hoá đơn"
          - Không phát hiện được ngày đặt đơn:
            -> "errorMessage": "Không tìm thấy ngày đặt đơn"

       c. Sai loại chứng từ:
          - Ảnh chỉ là biên lai quẹt thẻ POS ngân hàng (chỉ có mã giao dịch, số tiền trừ thẻ, không có danh sách từng món hàng chi tiết):
            -> "errorMessage": "Chứng từ là biên lai quẹt thẻ POS, không phải hóa đơn mua hàng chi tiết"
          - Ảnh chỉ là phiếu đặt cọc hoặc thanh toán một phần:
            -> "errorMessage": "Chứng từ là phiếu đặt cọc, không phải hóa đơn mua hàng hoàn chỉnh"

       d. Can thiệp vật lý & giả mạo hình ảnh:
          - Cố tình dùng ngón tay, bút hoặc vật thể che khuất mã hóa đơn, ngày giờ hoặc tổng tiền:
            -> "errorMessage": "Phát hiện thông tin mã bill hoặc thanh toán bị che khuất"
          - Dấu hiệu cắt ghép giấy vật lý, dán đè header/footer, hoặc viết tay/tẩy xóa chỉnh sửa số tiền:
            -> "errorMessage": "Phát hiện dấu hiệu cắt ghép vật lý hoặc chỉnh sửa số tiền"
          - Chụp lại từ màn hình thiết bị khác (màn hình máy tính/điện thoại hiển thị hóa đơn, có viền bezel hoặc vân sọc moiré/ánh sáng phẳng):
            -> "errorMessage": "Phát hiện ảnh chụp lại từ màn hình thiết bị khác"

       e. Chất lượng ảnh không đạt chuẩn:
          - Ảnh không phải hóa đơn hoặc quá mờ, mất nét, rung nhòe không thể đọc rõ danh sách món và số tiền:
            -> "errorMessage": "Hình ảnh không hợp lệ hoặc quá mờ"

    3. TRƯỜNG HỢP HỢP LỆ:
       - Các ảnh là các trang/góc chụp chân thực của cùng MỘT hóa đơn hợp lệ đã thanh toán:
         -> "statusCode": 200, "errorMessage": null, "receipt": {...}
         - Hãy gộp danh sách items và tự động khử trùng lặp các dòng hàng bị chụp lặp lại qua các ảnh.
""".trimIndent()

        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$geminiApiKey".trim()

        val partsList = mutableListOf<Map<String, Any>>()
        for (imgBase64 in base64Images) {
            partsList.add(
                mapOf(
                    "inline_data" to mapOf(
                        "mime_type" to "image/jpeg",
                        "data" to imgBase64
                    )
                )
            )
        }
        partsList.add(mapOf("text" to prompt))

        // Ép chặt JSON Schema để Gemini bắt buộc tuân theo
        val responseSchema = mapOf(
            "type" to "OBJECT",
            "properties" to mapOf(
                "statusCode" to mapOf(
                    "type" to "INTEGER",
                    "description" to "200 nếu hợp lệ, 400 nếu vi phạm gian lận hoặc ảnh lỗi"
                ),
                "errorMessage" to mapOf(
                    "type" to "STRING",
                    "nullable" to true,
                    "description" to "Thông báo lỗi nếu statusCode = 400"
                ),
                "receipt" to mapOf(
                    "type" to "OBJECT",
                    "nullable" to true,
                    "properties" to mapOf(
                        "storeName" to mapOf("type" to "STRING", "nullable" to true),
                        "receiptId" to mapOf("type" to "STRING", "nullable" to true),
                        "date" to mapOf("type" to "STRING", "nullable" to true),
                        "items" to mapOf(
                            "type" to "ARRAY",
                            "items" to mapOf(
                                "type" to "OBJECT",
                                "properties" to mapOf(
                                    "name" to mapOf("type" to "STRING"),
                                    "normalizedName" to mapOf("type" to "STRING", "nullable" to true),
                                    "quantity" to mapOf("type" to "NUMBER"),
                                    "unitPrice" to mapOf("type" to "NUMBER", "nullable" to true),
                                    "totalPrice" to mapOf("type" to "NUMBER")
                                ),
                                "required" to listOf("name", "quantity", "totalPrice")
                            )
                        ),
                        "subtotal" to mapOf("type" to "NUMBER", "nullable" to true),
                        "discount" to mapOf("type" to "NUMBER", "nullable" to true),
                        "tax" to mapOf("type" to "NUMBER", "nullable" to true),
                        "totalAmount" to mapOf("type" to "NUMBER")
                    ),
                    "required" to listOf("totalAmount", "items")
                )
            ),
            "required" to listOf("statusCode")
        )

        val requestPayloadMap = mapOf(
            "contents" to listOf(
                mapOf("parts" to partsList)
            ),
            "generationConfig" to mapOf(
                "response_mime_type" to "application/json",
                "response_schema" to responseSchema,
                "temperature" to 0.0
            )
        )

        val requestBody = Gson().toJson(requestPayloadMap).toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(endpoint)
            .post(requestBody)
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string()
            throw RuntimeException("Gemini API Error (${response.code}): $errorBody")
        }

        val responseString = response.body?.string() ?: throw RuntimeException("Empty response body")

        val parsedResponse = Gson().fromJson(responseString, Map::class.java)
        val candidates = parsedResponse["candidates"] as? List<*>
        val firstCandidate = candidates?.firstOrNull() as? Map<*, *>
        val content = firstCandidate?.get("content") as? Map<*, *>
        val parts = content?.get("parts") as? List<*>
        val firstPart = parts?.firstOrNull() as? Map<*, *>

        return@withContext firstPart?.get("text") as? String
            ?: throw RuntimeException("Không tìm thấy dữ liệu từ Gemini")
    }

    private fun resizeBitmapPreservingAspect(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxDimension && height <= maxDimension) return bitmap

        val ratio = width.toFloat() / height.toFloat()
        val targetWidth: Int
        val targetHeight: Int

        if (ratio > 1) {
            targetWidth = maxDimension
            targetHeight = (maxDimension / ratio).toInt()
        } else {
            targetHeight = maxDimension
            targetWidth = (maxDimension * ratio).toInt()
        }
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }
}

// ----------------------------------------------------------------------------
// 3. JETPACK COMPOSE UI
// ----------------------------------------------------------------------------

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF1E88E5))) {
                ReceiptScannerApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptScannerApp(viewModel: ReceiptScannerViewModel = viewModel()) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    val capturedUris = remember { mutableStateListOf<Uri>() }
    var pendingCaptureUri by remember { mutableStateOf<Uri?>(null) }

    fun createTempImageUri(): Uri {
        val tempFile = File.createTempFile("receipt_capture_", ".jpg", context.cacheDir).apply {
            createNewFile()
            deleteOnExit()
        }
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            tempFile
        )
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && pendingCaptureUri != null) {
            capturedUris.add(pendingCaptureUri!!)
            pendingCaptureUri = null
        }
    }

    // ========================================================================
    // POPUP DIALOG CẢNH BÁO LỖI 400 (CHỐNG GIAN LẬN)
    // ========================================================================
    if (uiState is ScannerUiState.FraudError) {
        val fraudState = uiState as ScannerUiState.FraudError
        AlertDialog(
            onDismissRequest = {
                // Đóng popup và reset lại trạng thái để người dùng chụp lại
                viewModel.resetState()
            },
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Cảnh báo gian lận",
                    tint = Color(0xFFD32F2F),
                    modifier = Modifier.size(44.dp)
                )
            },
            title = {
                Text(
                    text = "Cảnh Báo Gian Lận (${fraudState.statusCode})",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFD32F2F)
                )
            },
            text = {
                Text(
                    text = fraudState.message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFF37474F)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        capturedUris.clear()
                        viewModel.resetState()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                ) {
                    Text("Đã hiểu & Chụp lại", color = Color.White)
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Quét Hóa Đơn & Chống Gian Lận", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White
                ),
                actions = {
                    if (capturedUris.isNotEmpty() || uiState !is ScannerUiState.Idle) {
                        IconButton(onClick = {
                            capturedUris.clear()
                            viewModel.resetState()
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Làm mới", tint = Color.White)
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color(0xFFF8F9FA))
        ) {
            when (val state = uiState) {
                is ScannerUiState.Idle, is ScannerUiState.FraudError -> {
                    // Khi Idle hoặc khi FraudError (dialog nổi lên trên), giao diện nền vẫn giữ để thao tác
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        if (capturedUris.isEmpty()) {
                            EmptyPlaceholder(modifier = Modifier.weight(1f))
                        } else {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Ảnh đã chụp (${capturedUris.size})",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    capturedUris.forEachIndexed { index, uri ->
                                        CapturedImageThumbnail(
                                            uri = uri,
                                            index = index + 1,
                                            onRemove = { capturedUris.removeAt(index) }
                                        )
                                    }
                                }
                            }
                        }

                        ActionButtonsBar(
                            capturedCount = capturedUris.size,
                            onCaptureClick = {
                                val uri = createTempImageUri()
                                pendingCaptureUri = uri
                                cameraLauncher.launch(uri)
                            },
                            onAnalyzeClick = {
                                viewModel.processReceiptImages(context, capturedUris.toList())
                            }
                        )
                    }
                }

                is ScannerUiState.Loading -> {
                    LoadingView()
                }

                is ScannerUiState.SystemError -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        ErrorView(message = state.message)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { viewModel.resetState() }) {
                            Text("Thử lại")
                        }
                    }
                }

                is ScannerUiState.Success -> {
                    ReceiptResultView(receipt = state.receipt)
                }
            }
        }
    }
}

// ----------------------------------------------------------------------------
// 4. SUB-COMPONENTS
// ----------------------------------------------------------------------------

@Composable
fun CapturedImageThumbnail(
    uri: Uri,
    index: Int,
    onRemove: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(width = 120.dp, height = 160.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
    ) {
        AsyncImage(
            model = uri,
            contentDescription = "Ảnh $index",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        Surface(
            color = Color.Black.copy(alpha = 0.6f),
            shape = RoundedCornerShape(bottomEnd = 8.dp),
            modifier = Modifier.align(Alignment.TopStart)
        ) {
            Text(
                text = "#$index",
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }

        IconButton(
            onClick = onRemove,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(28.dp)
                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Xóa ảnh",
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
fun ActionButtonsBar(
    capturedCount: Int,
    onCaptureClick: () -> Unit,
    onAnalyzeClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedButton(
            onClick = onCaptureClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.AddAPhoto, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                if (capturedCount == 0) "Chụp ảnh hóa đơn" else "Chụp thêm góc/trang khác",
                fontWeight = FontWeight.SemiBold
            )
        }

        Button(
            onClick = onAnalyzeClick,
            enabled = capturedCount > 0,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF2E7D32),
                disabledContainerColor = Color.LightGray
            )
        ) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (capturedCount > 0) "Phân tích hóa đơn ($capturedCount ảnh)" else "Phân tích hóa đơn",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }
    }
}

@Composable
fun EmptyPlaceholder(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Receipt,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = Color.Gray.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Chưa có ảnh hóa đơn",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = Color.DarkGray
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Bấm 'Chụp ảnh hóa đơn' để bắt đầu. Hệ thống sẽ tự động đối chiếu các trang xem có cùng một số hóa đơn hay không.",
            textAlign = TextAlign.Center,
            color = Color.Gray,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
fun LoadingView() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(strokeWidth = 3.dp)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Đang kiểm tra tính hợp lệ & bóc tách hóa đơn...",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = Color.DarkGray
        )
    }
}

@Composable
fun ErrorView(message: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Lỗi",
                fontWeight = FontWeight.Bold,
                color = Color(0xFFC62828),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = message,
                color = Color(0xFFB71C1C),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
fun ReceiptResultView(receipt: ReceiptResponse) {
    val currencyFormat = NumberFormat.getCurrencyInstance(Locale("vi", "VN"))

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = receipt.storeName ?: "Hóa Đơn Bán Lẻ",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    receipt.receiptId?.let {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Mã hóa đơn: $it",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF37474F)
                        )
                    }
                    receipt.date?.let {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Ngày: $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                }
            }
        }

        item {
            Text(
                text = "Danh sách mặt hàng (${receipt.items.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.DarkGray,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
            )
        }

        items(receipt.items) { item ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.normalizedName ?: item.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF212121)
                        )
                        if (!item.normalizedName.isNullOrEmpty() && item.normalizedName != item.name) {
                            Text(
                                text = "Gốc: ${item.name}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "SL: ${if (item.quantity % 1.0 == 0.0) item.quantity.toInt() else item.quantity} " +
                                    (item.unitPrice?.let { "× " + currencyFormat.format(it) } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF616161)
                        )
                    }

                    Text(
                        text = currencyFormat.format(item.totalPrice),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2E7D32)
                    )
                }
            }
        }

        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    receipt.subtotal?.takeIf { it > 0 }?.let {
                        SummaryRow("Tạm tính:", currencyFormat.format(it))
                    }
                    receipt.discount?.takeIf { it > 0 }?.let {
                        SummaryRow("Giảm giá:", "-${currencyFormat.format(it)}")
                    }
                    receipt.tax?.takeIf { it > 0 }?.let {
                        SummaryRow("Thuế/VAT:", currencyFormat.format(it))
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = Color(0xFF90CAF9)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "TỔNG THANH TOÁN",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0D47A1)
                        )
                        Text(
                            text = currencyFormat.format(receipt.totalAmount),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0D47A1)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = Color.DarkGray)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}