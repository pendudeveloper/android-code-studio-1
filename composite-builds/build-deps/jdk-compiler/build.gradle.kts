/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

plugins {
  id("java-library")
  id("com.tom.rv2ide.build.propsparser")
}

java {
  sourceCompatibility = JavaVersion.VERSION_1_8
  targetCompatibility = JavaVersion.VERSION_1_8
}

// The vendored OpenJDK source ships an incomplete `sjavac` package (the server-side
// classes ServerMain/SjavacServer/PortFile/CompilationSubResult/SysInfo/Sjavac were
// not imported into this fork). The Android IDE never invokes those code paths, so
// we simply exclude the entire sjavac tree from compilation. The only non-sjavac
// reference is a Class.getName() string comparison in ClassFinder.java, which keeps
// working at runtime regardless of whether the class exists.
sourceSets {
  main {
    java {
      exclude("openjdk/tools/sjavac/**")
    }
  }
}

dependencies {
  api(projects.buildDeps.javaCompiler)
}