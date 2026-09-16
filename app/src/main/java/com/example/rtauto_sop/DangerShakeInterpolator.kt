package com.example.rtauto_sop

import android.view.animation.Interpolator
import kotlin.math.pow
import kotlin.math.sin

/**
 * 경보상세 카드가 "쿵" 하고 튕겼다가 잦아드는 흔들림에 쓰는 감쇠 사인 곡선.
 * [MainActivity.playAlertImpact]가 TRANSLATION_X를 0f -> 진폭(px)로 애니메이션할 때 이
 * 인터폴레이터를 물리면, 진폭 값과 곱해진 보간값이 좌우로 오가며 끝(t=1)에서 0으로
 * 수렴한다 — ObjectAnimator 자체는 두 값 사이만 보간하지만, 인터폴레이터가 음수를
 * 돌려주면 그 구간도 표현되므로 별도의 왕복 keyframe 없이 흔들림을 만들 수 있다.
 */
class DangerShakeInterpolator(
    private val cycles: Float = 4.5f,
    private val damping: Float = 1.8f
) : Interpolator {
    override fun getInterpolation(input: Float): Float {
        return (sin(2 * Math.PI * cycles * input) * (1.0 - input).pow(damping.toDouble())).toFloat()
    }
}
