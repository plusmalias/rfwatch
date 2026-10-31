package com.rfwatch.core.math

import kotlin.math.abs

/**
 * Minimal dense linear algebra for small systems (≤ 4×4 here): the normal equations of
 * the sweep least-squares fit and the Levenberg–Marquardt step of the localiser. Kept
 * dependency-free and square-matrix focused; not a general BLAS.
 *
 * Matrices are row-major `Array<DoubleArray>`.
 */
object LinAlg {

    /** C = A · B. */
    fun matMul(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
        val n = a.size
        val m = b[0].size
        val k = b.size
        require(a[0].size == k) { "inner dimensions must match" }
        return Array(n) { i ->
            DoubleArray(m) { j ->
                var s = 0.0
                for (p in 0 until k) s += a[i][p] * b[p][j]
                s
            }
        }
    }

    /** y = A · x. */
    fun matVec(a: Array<DoubleArray>, x: DoubleArray): DoubleArray =
        DoubleArray(a.size) { i ->
            var s = 0.0
            for (j in x.indices) s += a[i][j] * x[j]
            s
        }

    /** Aᵀ. */
    fun transpose(a: Array<DoubleArray>): Array<DoubleArray> {
        val rows = a.size
        val cols = a[0].size
        return Array(cols) { j -> DoubleArray(rows) { i -> a[i][j] } }
    }

    /**
     * Solve A·x = b for a square A via Gauss–Jordan elimination with partial pivoting.
     * Returns null if A is singular (no reliable solution).
     */
    fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = a.size
        val m = Array(n) { i -> DoubleArray(n + 1).also { row ->
            for (j in 0 until n) row[j] = a[i][j]
            row[n] = b[i]
        } }
        for (col in 0 until n) {
            // Partial pivot.
            var pivot = col
            for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[pivot][col])) pivot = r
            if (abs(m[pivot][col]) < 1e-12) return null
            val tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp
            val pv = m[col][col]
            for (j in col..n) m[col][j] /= pv
            for (r in 0 until n) {
                if (r == col) continue
                val f = m[r][col]
                if (f == 0.0) continue
                for (j in col..n) m[r][j] -= f * m[col][j]
            }
        }
        return DoubleArray(n) { i -> m[i][n] }
    }

    /**
     * Inverse of a square matrix via Gauss–Jordan with partial pivoting, or null if
     * singular. Used to turn the LM normal matrix into a parameter covariance.
     */
    fun inverse(a: Array<DoubleArray>): Array<DoubleArray>? {
        val n = a.size
        val m = Array(n) { i -> DoubleArray(2 * n).also { row ->
            for (j in 0 until n) row[j] = a[i][j]
            row[n + i] = 1.0
        } }
        for (col in 0 until n) {
            var pivot = col
            for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[pivot][col])) pivot = r
            if (abs(m[pivot][col]) < 1e-12) return null
            val tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp
            val pv = m[col][col]
            for (j in 0 until 2 * n) m[col][j] /= pv
            for (r in 0 until n) {
                if (r == col) continue
                val f = m[r][col]
                if (f == 0.0) continue
                for (j in 0 until 2 * n) m[r][j] -= f * m[col][j]
            }
        }
        return Array(n) { i -> DoubleArray(n) { j -> m[i][n + j] } }
    }
}
