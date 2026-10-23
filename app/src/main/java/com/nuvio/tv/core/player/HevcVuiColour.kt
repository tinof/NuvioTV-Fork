/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Fork: the SPS walk is ported from Media3 1.8.0
 * NalUnitUtil.parseH265SpsNalUnitPayload (libraries/container), up to the VUI colour
 * description, keeping matrix_coeffs, which Media3 skips.
 */
package com.nuvio.tv.core.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.container.ParsableNalUnitBitArray

/**
 * The raw colour fields of an H.265 SPS VUI (ITU-T H.265 E.2.1), as coded: ISO/IEC 23091-2 (H.273)
 * code points, not Media3 constants. [colourPrimaries], [transferCharacteristics] and [matrixCoeffs]
 * are null when colour_description_present_flag is 0; [videoFullRange] is meaningful only when
 * [videoSignalTypePresent] is set (when it is not, the range is untagged).
 */
@UnstableApi
internal data class HevcVuiColour(
    val generalProfileIdc: Int,
    val chromaFormatIdc: Int,
    val bitDepthLuma: Int,
    val bitDepthChroma: Int,
    val videoSignalTypePresent: Boolean,
    val videoFullRange: Boolean,
    val colourDescriptionPresent: Boolean,
    val colourPrimaries: Int?,
    val transferCharacteristics: Int?,
    val matrixCoeffs: Int?,
) {
    companion object {
        private const val NAL_TYPE_SPS = 33
        private const val EXTENDED_SAR = 255

        /**
         * Reads the SPS NAL in [nal] (2-byte header included, emulation prevention bytes as coded).
         * Returns null for an SPS it cannot read: not an SPS, nuh_layer_id above 0 (a multi-layer
         * SPS can take its format from the VPS), or truncated.
         */
        @JvmStatic
        fun parse(nal: ByteArray, offset: Int = 0, limit: Int = nal.size): HevcVuiColour? {
            if (limit - offset < 3) return null
            val nalType = (nal[offset].toInt() ushr 1) and 0x3F
            val layerId = ((nal[offset].toInt() and 0x01) shl 5) or ((nal[offset + 1].toInt() and 0xFF) ushr 3)
            if (nalType != NAL_TYPE_SPS || layerId != 0) return null
            return try {
                parsePayload(ParsableNalUnitBitArray(nal, offset + 2, limit))
            } catch (e: IllegalStateException) {
                // ParsableNalUnitBitArray asserts its offset stays inside the NAL.
                null
            } catch (e: IndexOutOfBoundsException) {
                null
            }
        }

        private fun parsePayload(data: ParsableNalUnitBitArray): HevcVuiColour? {
            data.skipBits(4) // sps_video_parameter_set_id
            val maxSubLayersMinus1 = data.readBits(3) // sps_max_sub_layers_minus1
            data.skipBit() // sps_temporal_id_nesting_flag
            val generalProfileIdc = parseProfileTierLevel(data, maxSubLayersMinus1)
            data.readUnsignedExpGolombCodedInt() // sps_seq_parameter_set_id
            val chromaFormatIdc = data.readUnsignedExpGolombCodedInt()
            if (chromaFormatIdc == 3) {
                data.skipBit() // separate_colour_plane_flag
            }
            data.readUnsignedExpGolombCodedInt() // pic_width_in_luma_samples
            data.readUnsignedExpGolombCodedInt() // pic_height_in_luma_samples
            if (data.readBit()) { // conformance_window_flag
                repeat(4) { data.readUnsignedExpGolombCodedInt() } // conf_win_*_offset
            }
            val bitDepthLuma = data.readUnsignedExpGolombCodedInt() + 8
            val bitDepthChroma = data.readUnsignedExpGolombCodedInt() + 8
            val log2MaxPicOrderCntLsbMinus4 = checkRange(data.readUnsignedExpGolombCodedInt(), 12)
            // for (i = sps_sub_layer_ordering_info_present_flag ? 0 : sps_max_sub_layers_minus1; ...)
            var i = if (data.readBit()) 0 else maxSubLayersMinus1
            while (i <= maxSubLayersMinus1) {
                data.readUnsignedExpGolombCodedInt() // sps_max_dec_pic_buffering_minus1[i]
                data.readUnsignedExpGolombCodedInt() // sps_max_num_reorder_pics[i]
                data.readUnsignedExpGolombCodedInt() // sps_max_latency_increase_plus1[i]
                i++
            }
            data.readUnsignedExpGolombCodedInt() // log2_min_luma_coding_block_size_minus3
            data.readUnsignedExpGolombCodedInt() // log2_diff_max_min_luma_coding_block_size
            data.readUnsignedExpGolombCodedInt() // log2_min_luma_transform_block_size_minus2
            data.readUnsignedExpGolombCodedInt() // log2_diff_max_min_luma_transform_block_size
            data.readUnsignedExpGolombCodedInt() // max_transform_hierarchy_depth_inter
            data.readUnsignedExpGolombCodedInt() // max_transform_hierarchy_depth_intra
            if (data.readBit()) { // scaling_list_enabled_flag
                if (data.readBit()) { // sps_scaling_list_data_present_flag
                    skipScalingList(data)
                }
            }
            data.skipBits(2) // amp_enabled_flag, sample_adaptive_offset_enabled_flag
            if (data.readBit()) { // pcm_enabled_flag
                data.skipBits(8) // pcm_sample_bit_depth_luma_minus1, pcm_sample_bit_depth_chroma_minus1
                data.readUnsignedExpGolombCodedInt() // log2_min_pcm_luma_coding_block_size_minus3
                data.readUnsignedExpGolombCodedInt() // log2_diff_max_min_pcm_luma_coding_block_size
                data.skipBit() // pcm_loop_filter_disabled_flag
            }
            skipShortTermReferencePictureSets(data)
            if (data.readBit()) { // long_term_ref_pics_present_flag
                val numLongTermRefPicsSps = checkRange(data.readUnsignedExpGolombCodedInt(), 32)
                repeat(numLongTermRefPicsSps) {
                    // lt_ref_pic_poc_lsb_sps[i], used_by_curr_pic_lt_sps_flag[i]
                    data.skipBits(log2MaxPicOrderCntLsbMinus4 + 4 + 1)
                }
            }
            data.skipBits(2) // sps_temporal_mvp_enabled_flag, strong_intra_smoothing_enabled_flag
            var videoSignalTypePresent = false
            var videoFullRange = false
            var colourDescriptionPresent = false
            var colourPrimaries: Int? = null
            var transferCharacteristics: Int? = null
            var matrixCoeffs: Int? = null
            if (data.readBit()) { // vui_parameters_present_flag
                if (data.readBit()) { // aspect_ratio_info_present_flag
                    if (data.readBits(8) == EXTENDED_SAR) { // aspect_ratio_idc
                        data.skipBits(32) // sar_width, sar_height
                    }
                }
                if (data.readBit()) { // overscan_info_present_flag
                    data.skipBit() // overscan_appropriate_flag
                }
                videoSignalTypePresent = data.readBit()
                if (videoSignalTypePresent) {
                    data.skipBits(3) // video_format
                    videoFullRange = data.readBit()
                    colourDescriptionPresent = data.readBit()
                    if (colourDescriptionPresent) {
                        colourPrimaries = data.readBits(8)
                        transferCharacteristics = data.readBits(8)
                        matrixCoeffs = data.readBits(8)
                    }
                }
            }
            return HevcVuiColour(
                generalProfileIdc = generalProfileIdc,
                chromaFormatIdc = chromaFormatIdc,
                bitDepthLuma = bitDepthLuma,
                bitDepthChroma = bitDepthChroma,
                videoSignalTypePresent = videoSignalTypePresent,
                videoFullRange = videoFullRange,
                colourDescriptionPresent = colourDescriptionPresent,
                colourPrimaries = colourPrimaries,
                transferCharacteristics = transferCharacteristics,
                matrixCoeffs = matrixCoeffs,
            )
        }

        /**
         * Fork: bounds a count read from the SPS to its H.265 7.4.3.2 limit, so a corrupt SPS ends the
         * walk instead of allocating or looping on a garbage value (Media3 has no such check).
         */
        private fun checkRange(value: Int, max: Int): Int {
            check(value in 0..max) { "SPS value $value outside 0..$max" }
            return value
        }

        /** Reads profile_tier_level(1, maxSubLayersMinus1) and returns general_profile_idc. */
        private fun parseProfileTierLevel(data: ParsableNalUnitBitArray, maxSubLayersMinus1: Int): Int {
            data.skipBits(2) // general_profile_space
            data.skipBit() // general_tier_flag
            val generalProfileIdc = data.readBits(5)
            data.skipBits(32) // general_profile_compatibility_flag[32]
            data.skipBits(48) // general constraint flags
            data.skipBits(8) // general_level_idc
            var toSkip = 0
            for (i in 0 until maxSubLayersMinus1) {
                if (data.readBit()) toSkip += 88 // sub_layer_profile_present_flag[i]
                if (data.readBit()) toSkip += 8 // sub_layer_level_present_flag[i]
            }
            data.skipBits(toSkip)
            if (maxSubLayersMinus1 > 0) {
                data.skipBits(2 * (8 - maxSubLayersMinus1)) // reserved_zero_2bits
            }
            return generalProfileIdc
        }

        private fun skipScalingList(data: ParsableNalUnitBitArray) {
            for (sizeId in 0 until 4) {
                var matrixId = 0
                while (matrixId < 6) {
                    if (!data.readBit()) { // scaling_list_pred_mode_flag[sizeId][matrixId]
                        data.readUnsignedExpGolombCodedInt() // scaling_list_pred_matrix_id_delta
                    } else {
                        val coefNum = minOf(64, 1 shl (4 + (sizeId shl 1)))
                        if (sizeId > 1) {
                            data.readSignedExpGolombCodedInt() // scaling_list_dc_coef_minus8
                        }
                        repeat(coefNum) { data.readSignedExpGolombCodedInt() } // scaling_list_delta_coef
                    }
                    matrixId += if (sizeId == 3) 3 else 1
                }
            }
        }

        /**
         * Skips the st_ref_pic_set()s of an SPS. In an SPS each set can only be predicted from the
         * one before it (delta_idx_minus1 is 0), so only that one's counts and deltas are kept.
         */
        private fun skipShortTermReferencePictureSets(data: ParsableNalUnitBitArray) {
            val numShortTermRefPicSets = checkRange(data.readUnsignedExpGolombCodedInt(), 64)
            var previousNumNegativePics = -1
            var previousNumPositivePics = -1
            var previousDeltaPocS0 = IntArray(0)
            var previousDeltaPocS1 = IntArray(0)
            for (stRpsIdx in 0 until numShortTermRefPicSets) {
                val numNegativePics: Int
                val numPositivePics: Int
                var deltaPocS0: IntArray
                var deltaPocS1: IntArray
                val interRefPicSetPredictionFlag = stRpsIdx != 0 && data.readBit()
                if (interRefPicSetPredictionFlag) {
                    val previousNumDeltaPocs = previousNumNegativePics + previousNumPositivePics
                    val deltaRpsSign = if (data.readBit()) 1 else 0
                    val absDeltaRps = data.readUnsignedExpGolombCodedInt() + 1
                    val deltaRps = (1 - 2 * deltaRpsSign) * absDeltaRps
                    val useDeltaFlags = BooleanArray(previousNumDeltaPocs + 1)
                    for (j in 0..previousNumDeltaPocs) {
                        // used_by_curr_pic_flag[j]; use_delta_flag[j] is 1 when not present.
                        useDeltaFlags[j] = if (!data.readBit()) data.readBit() else true
                    }
                    // H.265 7.4.8: derive the negative and positive POC deltas of this set.
                    var n = 0
                    deltaPocS0 = IntArray(previousNumDeltaPocs + 1)
                    deltaPocS1 = IntArray(previousNumDeltaPocs + 1)
                    for (j in previousNumPositivePics - 1 downTo 0) {
                        val dPoc = previousDeltaPocS1[j] + deltaRps
                        if (dPoc < 0 && useDeltaFlags[previousNumNegativePics + j]) deltaPocS0[n++] = dPoc
                    }
                    if (deltaRps < 0 && useDeltaFlags[previousNumDeltaPocs]) deltaPocS0[n++] = deltaRps
                    for (j in 0 until previousNumNegativePics) {
                        val dPoc = previousDeltaPocS0[j] + deltaRps
                        if (dPoc < 0 && useDeltaFlags[j]) deltaPocS0[n++] = dPoc
                    }
                    numNegativePics = n
                    deltaPocS0 = deltaPocS0.copyOf(numNegativePics)
                    n = 0
                    for (j in previousNumNegativePics - 1 downTo 0) {
                        val dPoc = previousDeltaPocS0[j] + deltaRps
                        if (dPoc > 0 && useDeltaFlags[j]) deltaPocS1[n++] = dPoc
                    }
                    if (deltaRps > 0 && useDeltaFlags[previousNumDeltaPocs]) deltaPocS1[n++] = deltaRps
                    for (j in 0 until previousNumPositivePics) {
                        val dPoc = previousDeltaPocS1[j] + deltaRps
                        if (dPoc > 0 && useDeltaFlags[previousNumNegativePics + j]) deltaPocS1[n++] = dPoc
                    }
                    numPositivePics = n
                    deltaPocS1 = deltaPocS1.copyOf(numPositivePics)
                } else {
                    numNegativePics = checkRange(data.readUnsignedExpGolombCodedInt(), 16)
                    numPositivePics = checkRange(data.readUnsignedExpGolombCodedInt(), 16)
                    deltaPocS0 = IntArray(numNegativePics)
                    for (k in 0 until numNegativePics) {
                        deltaPocS0[k] = (if (k > 0) deltaPocS0[k - 1] else 0) -
                            (data.readUnsignedExpGolombCodedInt() + 1)
                        data.skipBit() // used_by_curr_pic_s0_flag[k]
                    }
                    deltaPocS1 = IntArray(numPositivePics)
                    for (k in 0 until numPositivePics) {
                        deltaPocS1[k] = (if (k > 0) deltaPocS1[k - 1] else 0) +
                            (data.readUnsignedExpGolombCodedInt() + 1)
                        data.skipBit() // used_by_curr_pic_s1_flag[k]
                    }
                }
                previousNumNegativePics = numNegativePics
                previousNumPositivePics = numPositivePics
                previousDeltaPocS0 = deltaPocS0
                previousDeltaPocS1 = deltaPocS1
            }
        }
    }
}
