package com.kinde.playground.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.automirrored.outlined.Rule
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.ui.graphics.vector.ImageVector

enum class Page(val title: String, val blurb: String, val icon: ImageVector) {
    HOME("Kinde Playground", "", Icons.Outlined.Dashboard),
    DASHBOARD("Dashboard", "Configuration and live setup checks", Icons.Outlined.Dashboard),
    SIGN_IN("Sign-in lab", "login, register, createOrg, invitations, logout with every option", Icons.AutoMirrored.Outlined.Login),
    PROFILE("Profile", "getUserDetails, getUser, getUserProfileV2", Icons.Outlined.Person),
    TOKENS("Tokens", "Access, ID and refresh tokens, expiry, refresh schedule", Icons.Outlined.Key),
    CLAIMS("Claims", "getClaim for either token, every claim decoded", Icons.Outlined.DataObject),
    ACCESS("Permissions & roles", "Token claims and the Account API side by side, caching", Icons.Outlined.Badge),
    FLAGS("Feature flags", "getFlag and typed flag helpers, token vs Account API", Icons.Outlined.Flag),
    BILLING("Billing & entitlements", "getEntitlements, getEntitlement, pricing tables", Icons.Outlined.CreditCard),
    ORGS("Organizations", "Current org, memberships, switching and creating orgs", Icons.Outlined.Business),
    BACKGROUND("Background access", "KindeClient from a worker and an OkHttp interceptor", Icons.Outlined.Sync),
    LIFECYCLE("Activities & lifecycle", "One client across activities, rotation and listeners", Icons.Outlined.AccountTree),
    MANAGEMENT("Management API", "getUsers and createUser (deprecated) through the SDK", Icons.Outlined.AdminPanelSettings),
    SECURITY("Security audit", "Storage, PKCE, logs, forged redirects", Icons.Outlined.Security),
    REGRESSION("Regression", "On-device and live checks, plus the host suite results", Icons.AutoMirrored.Outlined.Rule),
    EVENTS("SDK events", "Every SDKListener callback, with its thread", Icons.Outlined.Terminal),
    ;

    companion object {
        val menu = entries.filter { it != HOME }
        fun from(name: String?) = entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: HOME
    }
}
