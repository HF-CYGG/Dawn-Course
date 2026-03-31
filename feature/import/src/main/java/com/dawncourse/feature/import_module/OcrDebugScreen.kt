package com.dawncourse.feature.import_module

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dawncourse.feature.import_module.engine.ocr.GridCell
import com.dawncourse.feature.import_module.engine.ocr.TextBlock
import com.dawncourse.feature.import_module.model.ParsedCourse

/**
 * OCR 识别结果可视化调试界面
 *
 * 用于显示 OCR 识别的详细过程和结果，帮助开发者和用户理解识别过程中可能出现的问题。
 */
@Composable
fun OcrDebugScreen(
    originalBitmap: Bitmap,
    processedBitmap: Bitmap,
    textBlocks: List<TextBlock>,
    gridCells: List<GridCell>,
    parsedCourses: List<ParsedCourse>,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OCR 识别调试") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                }
            )
        }
    ) {
        LazyColumn(
            modifier = Modifier
                .padding(it)
                .fillMaxSize()
        ) {
            item {
                OcrDebugSection(
                    title = "原始图像",
                    content = {
                        Image(
                            bitmap = originalBitmap.asImageBitmap(),
                            contentDescription = "原始图像",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp)
                                .padding(16.dp)
                        )
                    }
                )
            }

            item {
                OcrDebugSection(
                    title = "处理后图像",
                    content = {
                        Image(
                            bitmap = processedBitmap.asImageBitmap(),
                            contentDescription = "处理后图像",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp)
                                .padding(16.dp)
                        )
                    }
                )
            }

            item {
                OcrDebugSection(
                    title = "OCR 识别结果",
                    content = {
                        val annotatedBitmap = drawTextBlocks(originalBitmap, textBlocks)
                        Image(
                            bitmap = annotatedBitmap.asImageBitmap(),
                            contentDescription = "OCR 识别结果",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp)
                                .padding(16.dp)
                        )
                        
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("识别到的文本块数量: ${textBlocks.size}")
                            if (textBlocks.isNotEmpty()) {
                                Text("前5个文本块:")
                                textBlocks.take(5).forEachIndexed { index, block ->
                                    Text("${index + 1}. ${block.text} (${block.boundingBox.left}, ${block.boundingBox.top})-(${block.boundingBox.right}, ${block.boundingBox.bottom})")
                                }
                            }
                        }
                    }
                )
            }

            item {
                OcrDebugSection(
                    title = "网格分析结果",
                    content = {
                        val gridBitmap = drawGridCells(originalBitmap, gridCells)
                        Image(
                            bitmap = gridBitmap.asImageBitmap(),
                            contentDescription = "网格分析结果",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp)
                                .padding(16.dp)
                        )
                        
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("识别到的网格单元格数量: ${gridCells.size}")
                            if (gridCells.isNotEmpty()) {
                                Text("前5个单元格:")
                                gridCells.take(5).forEachIndexed { index, cell ->
                                    Text("${index + 1}. 第${cell.startSection}节 星期${cell.dayOfWeek} - ${cell.blocks.size}个文本块")
                                    cell.blocks.forEach { block ->
                                        Text("  - ${block.text}")
                                    }
                                }
                            }
                        }
                    }
                )
            }

            item {
                OcrDebugSection(
                    title = "课程解析结果",
                    content = {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("解析到的课程数量: ${parsedCourses.size}")
                            if (parsedCourses.isNotEmpty()) {
                                parsedCourses.forEachIndexed { index, course ->
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(8.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                                        )
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Text("${index + 1}. ${course.name}", fontWeight = MaterialTheme.typography.bodyMedium.fontWeight)
                                            Text("教师: ${course.teacher}")
                                            Text("地点: ${course.location}")
                                            Text("时间: 星期${course.dayOfWeek} 第${course.startSection}-${course.startSection + course.duration - 1}节")
                                            Text("周次: ${course.startWeek}-${course.endWeek}周 ${if (course.weekType == 1) "单周" else if (course.weekType == 2) "双周" else "全周"}")
                                            Text("置信度: ${String.format("%.2f", course.confidence)}")
                                        }
                                    }
                                }
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun OcrDebugSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .padding(16.dp)
                .padding(bottom = 8.dp)
        )
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            content()
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

private fun drawTextBlocks(bitmap: Bitmap, textBlocks: List<TextBlock>): Bitmap {
    val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(result)
    
    val paint = Paint().apply {
        color = Color.RED
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    
    val textPaint = Paint().apply {
        color = Color.RED
        textSize = 24f
    }
    
    textBlocks.forEachIndexed { index, block ->
        canvas.drawRect(
            block.boundingBox.left.toFloat(),
            block.boundingBox.top.toFloat(),
            block.boundingBox.right.toFloat(),
            block.boundingBox.bottom.toFloat(),
            paint
        )
        canvas.drawText(
            "$index",
            block.boundingBox.left.toFloat(),
            block.boundingBox.top.toFloat() - 10,
            textPaint
        )
    }
    
    return result
}

private fun drawGridCells(bitmap: Bitmap, gridCells: List<GridCell>): Bitmap {
    val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(result)
    
    val paint = Paint().apply {
        color = Color.BLUE
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    
    val textPaint = Paint().apply {
        color = Color.BLUE
        textSize = 24f
    }
    
    gridCells.forEachIndexed { index, cell ->
        // 简化处理：使用第一个文本块的边界作为单元格边界
        if (cell.blocks.isNotEmpty()) {
            val block = cell.blocks[0]
            canvas.drawRect(
                block.boundingBox.left.toFloat(),
                block.boundingBox.top.toFloat(),
                block.boundingBox.right.toFloat(),
                block.boundingBox.bottom.toFloat(),
                paint
            )
            canvas.drawText(
                "${cell.startSection}-${cell.dayOfWeek}",
                block.boundingBox.left.toFloat(),
                block.boundingBox.top.toFloat() - 10,
                textPaint
            )
        }
    }
    
    return result
}
