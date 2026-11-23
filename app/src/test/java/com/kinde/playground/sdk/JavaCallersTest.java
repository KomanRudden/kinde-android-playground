package com.kinde.playground.sdk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import au.kinde.sdk.api.model.UserProfileV2;

import org.junit.Test;

/** SDK calls written in Java source, which Kotlin-only changes can break without any Kotlin test noticing. */
public class JavaCallersTest {

    @Test
    public void javaCanStillBuildAUserProfileV2WithTheOriginalSixFields() {
        UserProfileV2 profile = new UserProfileV2("kp_1", null, "Ann Lee", "Ann", "Lee", "1700000000");

        assertEquals("Ann", profile.getGivenName());
        assertNull(profile.getEmail());
    }
}
