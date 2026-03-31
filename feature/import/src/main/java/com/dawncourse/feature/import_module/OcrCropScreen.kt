package com.dawncourse.feature.import_module

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * OCR 课表图片裁剪页面
 *
 * 强制用户裁剪课表图片，仅保留课表主体，以极大提高 OCR 识别准确率。
 * 支持通过拖拽边框调整裁剪区域。
 */
@Composable
fun OcrCropScreen(
    bitmap: Bitmap,
    onCancel: () -> Unit,
    onCropConfirm: (Bitmap) -> Unit
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    
    // 裁剪框归一化坐标 (0.0 - 1.0)
    var cropRect by remember { mutableStateOf(Rect(0.1f, 0.1f, 0.9f, 0.9f)) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 顶部提示
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 32.dp)
        ) {
            Text(
                text = "请拖拽边框，仅保留课表主体区域",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = "提示：\n1. 确保只包含课表表格部分\n2. 移除顶部标题、底部导航等无关内容\n3. 保证课表文字清晰可见\n4. 尽量保持课表水平",
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )
        }

        // 图片与裁剪交互区
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 100.dp, horizontal = 16.dp)
                .onGloballyPositioned { containerSize = it.size },
            contentAlignment = Alignment.Center
        ) {
            if (containerSize.width > 0 && containerSize.height > 0) {
                // 计算图片在容器中的实际显示大小和偏移
                val imageAspect = bitmap.width.toFloat() / bitmap.height
                val containerAspect = containerSize.width.toFloat() / containerSize.height
                
                val drawWidth: Float
                val drawHeight: Float
                if (imageAspect > containerAspect) {
                    drawWidth = containerSize.width.toFloat()
                    drawHeight = drawWidth / imageAspect
                } else {
                    drawHeight = containerSize.height.toFloat()
                    drawWidth = drawHeight * imageAspect
                }
                
                val offsetX = (containerSize.width - drawWidth) / 2f
                val offsetY = (containerSize.height - drawHeight) / 2f

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                // 将拖拽量转换为归一化比例
                                val dx = dragAmount.x / drawWidth
                                val dy = dragAmount.y / drawHeight
                                
                                val pos = change.position
                                // 判断拖拽点靠近哪条边
                                val touchNormX = (pos.x - offsetX) / drawWidth
                                val touchNormY = (pos.y - offsetY) / drawHeight
                                
                                val threshold = 0.1f
                                var newLeft = cropRect.left
                                var newTop = cropRect.top
                                var newRight = cropRect.right
                                var newBottom = cropRect.bottom
                                
                                if (kotlin.math.abs(touchNormX - cropRect.left) < threshold) {
                                    newLeft = (cropRect.left + dx).coerceIn(0f, cropRect.right - 0.1f)
                                } else if (kotlin.math.abs(touchNormX - cropRect.right) < threshold) {
                                    newRight = (cropRect.right + dx).coerceIn(cropRect.left + 0.1f, 1f)
                                }
                                
                                if (kotlin.math.abs(touchNormY - cropRect.top) < threshold) {
                                    newTop = (cropRect.top + dy).coerceIn(0f, cropRect.bottom - 0.1f)
                                } else if (kotlin.math.abs(touchNormY - cropRect.bottom) < threshold) {
                                    newBottom = (cropRect.bottom + dy).coerceIn(cropRect.top + 0.1f, 1f)
                                }
                                
                                cropRect = Rect(newLeft, newTop, newRight, newBottom)
                            }
                        }
                ) {
                    // 1. 绘制底层图片
                    drawImage(
                        image = bitmap.asImageBitmap(),
                        dstOffset = androidx.compose.ui.unit.IntOffset(offsetX.toInt(), offsetY.toInt()),
                        dstSize = androidx.compose.ui.unit.IntSize(drawWidth.toInt(), drawHeight.toInt())
                    )
                    
                    // 2. 绘制半透明遮罩 (使用4个矩形避免BlendMode.Clear清除底层图片)
                    val actualCropRect = Rect(
                        left = offsetX + cropRect.left * drawWidth,
                        top = offsetY + cropRect.top * drawHeight,
                        right = offsetX + cropRect.right * drawWidth,
                        bottom = offsetY + cropRect.bottom * drawHeight
                    )
                    
                    val maskColor = Color.Black.copy(alpha = 0.6f)
                    // Top
                    drawRect(
                        color = maskColor,
                        topLeft = Offset.Zero,
                        size = Size(size.width, actualCropRect.top)
                    )
                    // Bottom
                    drawRect(
                        color = maskColor,
                        topLeft = Offset(0f, actualCropRect.bottom),
                        size = Size(size.width, size.height - actualCropRect.bottom)
                    )
                    // Left
                    drawRect(
                        color = maskColor,
                        topLeft = Offset(0f, actualCropRect.top),
                        size = Size(actualCropRect.left, actualCropRect.height)
                    )
                    // Right
                    drawRect(
                        color = maskColor,
                        topLeft = Offset(actualCropRect.right, actualCropRect.top),
                        size = Size(size.width - actualCropRect.right, actualCropRect.height)
                    )
                    
                    // 3. 绘制裁剪框边缘和角标
                    drawRect(
                        color = Color.White,
                        topLeft = actualCropRect.topLeft,
                        size = actualCropRect.size,
                        style = Stroke(width = 2.dp.toPx())
                    )
                    
                    // 绘制九宫格辅助线
                    val thirdWidth = actualCropRect.width / 3
                    val thirdHeight = actualCropRect.height / 3
                    for (i in 1..2) {
                        drawLine(
                            color = Color.White.copy(alpha = 0.5f),
                            start = Offset(actualCropRect.left + thirdWidth * i, actualCropRect.top),
                            end = Offset(actualCropRect.left + thirdWidth * i, actualCropRect.bottom),
                            strokeWidth = 1.dp.toPx()
                        )
                        drawLine(
                            color = Color.White.copy(alpha = 0.5f),
                            start = Offset(actualCropRect.left, actualCropRect.top + thirdHeight * i),
                            end = Offset(actualCropRect.right, actualCropRect.top + thirdHeight * i),
                            strokeWidth = 1.dp.toPx()
                        )
                    }
                }
            }
        }

        // 底部操作栏
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(32.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            TextButton(onClick = onCancel) {
                Text("取消", color = Color.White, fontSize = 16.sp)
            }
            Button(
                onClick = {
                    // 执行真实的 Bitmap 裁剪
                    val cropLeft = (cropRect.left * bitmap.width).toInt()
                    val cropTop = (cropRect.top * bitmap.height).toInt()
                    val cropWidth = (cropRect.width * bitmap.width).toInt()
                    val cropHeight = (cropRect.height * bitmap.height).toInt()
                    
                    val croppedBitmap = Bitmap.createBitmap(
                        bitmap,
                        cropLeft.coerceAtLeast(0),
                        cropTop.coerceAtLeast(0),
                        cropWidth.coerceAtMost(bitmap.width - cropLeft),
                        cropHeight.coerceAtMost(bitmap.height - cropTop)
                    )
                    onCropConfirm(croppedBitmap)
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("确认裁剪并识别", fontSize = 16.sp)
            }
        }
    }
}
