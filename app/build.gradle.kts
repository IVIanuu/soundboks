/*
 * Copyright 2022 Manuel Wrage. Use of this source code is governed by the Apache 2.0 license.
 */

/*
 * Copyright 2021 Manuel Wrage
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

plugins {
  id("com.android.application")
  id("com.ivianuu.essentials")
  kotlin("android")
}

android {
  namespace = "com.ivianuu.soundboks"
  compileSdk = Build.compileSdk
  defaultConfig {
    minSdk = Build.minSdk
    targetSdk = Build.targetSdk
    versionName = Build.versionName
    versionCode = Build.versionCode
  }

  buildTypes {
    getByName("release") {
      isMinifyEnabled = true
      proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
      isShrinkResources = true
    }
  }
}

dependencies {
  implementation(Deps.Essentials.android)
  implementation(Deps.Essentials.rubik)
}
