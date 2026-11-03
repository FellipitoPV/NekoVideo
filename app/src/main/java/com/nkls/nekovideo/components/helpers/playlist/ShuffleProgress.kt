package com.nkls.nekovideo.components.helpers

internal data class ShuffleProgress(val stage: Stage = Stage.FILTERING, val percent: Int = 0,
                                    val completed: Int = 0, val total: Int = 0) {
    enum class Stage { FILTERING, DISCOVERING, PRIVATE_ACCESS, COLLECTING, BUILDING }
}
