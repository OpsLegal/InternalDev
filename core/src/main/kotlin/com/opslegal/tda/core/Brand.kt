package com.opslegal.tda.core

/**
 * The app's name, in one place: every text the user sees and the folder it creates in a connected drive use it.
 * To rename the app: change [NAME] here and app_name in app/src/main/res/values/strings.xml. Never change the
 * applicationId (com.opslegal.tda): Google Play and the Microsoft sign-in are tied to it.
 */
object Brand {
    const val NAME = "Docket 5"

    /** The folder created in a connected drive (OneDrive › Apps › [FOLDER]). */
    const val FOLDER = NAME
}
