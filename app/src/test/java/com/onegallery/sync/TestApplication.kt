package com.onegallery.sync

import android.app.Application

/** Evita MapLibre/MSAL no Robolectric — só testes de helpers locais. */
class TestApplication : Application()
