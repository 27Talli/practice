package com.offerpoint.pos

import android.app.Application
import com.offerpoint.pos.di.AppContainer

class OfferPointApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
