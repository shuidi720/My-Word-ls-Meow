package io.catpaw.kittytalk.text

import io.catpaw.kittytalk.Suffix
import kotlin.random.Random

/**
 * 句尾附加文字的带权重随机选择器：
 *  - 权重 = 后缀文本长度 + 1（长后缀略占优）
 *  - 每次调用 next() 都重新随机，避免轮询导致同一后缀连续出现
 */
class SuffixPicker(suffixes: List<Suffix>) {

    private val pool: List<String> = suffixes.map { it.text }.filter { it.isNotBlank() }
    private val weights: IntArray = pool.map { it.length + 1 }.toIntArray()
    private val totalWeight: Int = weights.sum()

    val isEmpty: Boolean = pool.isEmpty()

    fun next(): String {
        if (pool.isEmpty()) return ""
        if (pool.size == 1) return pool[0]
        var r = Random.nextInt(totalWeight)
        for (i in weights.indices) {
            r -= weights[i]
            if (r < 0) return pool[i]
        }
        return pool[pool.size - 1]
    }
}
