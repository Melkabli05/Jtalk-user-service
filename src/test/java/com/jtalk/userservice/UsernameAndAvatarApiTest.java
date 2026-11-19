package com.jtalk.userservice;

import com.jtalk.userservice.support.ApiTestBase;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.transaction.SynchronousTransactionManager;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsernameAndAvatarApiTest extends ApiTestBase {

    private static final Pattern UPLOADED = Pattern.compile("/api/v1/users/[0-9a-f-]{36}/avatar/\\d+");

    @Inject
    SynchronousTransactionManager<Connection> transactions;

    // ---- username ----------------------------------------------------------------------------------

    @Test
    void aUsernameCanBeChangedAndTheOldOneIsFreeAgain() {
        register("sara");

        Map<String, Object> updated = patch("/api/v1/users/me", "sara", Map.of("username", "sara.new"));

        assertEquals("sara.new", updated.get("username"));
        assertEquals("sara.new", get(userPath("sara", ""), "someone").get("username"), "the next read sees it");
        Map<String, Object> omar = post("/api/v1/users/me", "omar",
                Map.of("username", "sara", "displayName", "Omar", "countryCode", "MA", "nativeLanguage", "ar"));
        assertEquals("sara", omar.get("username"), "the old handle is free for someone else at once");
    }

    @Test
    void aSecondChangeIsRefusedUntilTheIntervalHasPassed() {
        register("sara");
        patch("/api/v1/users/me", "sara", Map.of("username", "sara.one"));

        Map<String, Object> problem = assertProblem(HttpStatus.UNPROCESSABLE_ENTITY, "username-change-too-soon",
                () -> patch("/api/v1/users/me", "sara", Map.of("username", "sara.two")));
        assertTrue(problem.get("detail").toString().contains("again on"), problem.toString());
        assertEquals("sara.one", get("/api/v1/users/me", "sara").get("username"));

        longAgo("sara");
        assertEquals("sara.two", patch("/api/v1/users/me", "sara", Map.of("username", "sara.two")).get("username"));
    }

    @Test
    void aTakenOrMalformedUsernameIsRefusedAndKeepingTheSameOneIsFree() {
        register("sara");
        register("omar");

        assertProblem(HttpStatus.CONFLICT, "username-taken", () -> patch("/api/v1/users/me", "sara", Map.of("username", username("omar"))));
        assertThrows(HttpClientResponseException.class, () -> patch("/api/v1/users/me", "sara", Map.of("username", "No Spaces!")));

        // Sending the username it already has changes nothing and does not start the waiting time.
        patch("/api/v1/users/me", "sara", Map.of("username", username("sara"), "displayName", "Sara"));
        assertEquals("sara.fresh", patch("/api/v1/users/me", "sara", Map.of("username", "sara.fresh")).get("username"));
    }

    // ---- uploaded pictures -------------------------------------------------------------------------

    @Test
    void anUploadedPictureBecomesTheProfilePictureAndAnyoneCanFetchIt() {
        register("sara");
        byte[] picture = image("jpg", 256, 256);

        Map<String, Object> profile = http.retrieve(HttpRequest.PUT("/api/v1/users/me/avatar", picture)
                .contentType(MediaType.IMAGE_JPEG_TYPE).bearerAuth(token("sara")), MAP);

        String address = (String) profile.get("avatarUrl");
        assertTrue(UPLOADED.matcher(address).matches(), address);
        assertTrue(address.contains(id("sara").toString()));

        // No token at all: an <img> cannot send one.
        HttpResponse<byte[]> fetched = http.exchange(HttpRequest.GET(address), byte[].class);
        assertEquals(HttpStatus.OK, fetched.status());
        assertEquals("image/jpeg", fetched.getContentType().orElseThrow().toString());
        assertArrayEquals(picture, fetched.body());
        assertTrue(fetched.header("Cache-Control").contains("immutable"), "this version may be kept for ever");

        // An address with an older version still answers, with the current picture, for a short time.
        HttpResponse<byte[]> stale = http.exchange(HttpRequest.GET(userPath("sara", "/avatar/1")), byte[].class);
        assertEquals(HttpStatus.OK, stale.status());
        assertEquals("public, max-age=300", stale.header("Cache-Control"));
    }

    @Test
    void aNewPictureReplacesTheOldOneAtANewAddress() throws InterruptedException {
        register("sara");
        String first = upload("sara", image("jpg", 128, 128), MediaType.IMAGE_JPEG_TYPE);
        Thread.sleep(5);
        String second = upload("sara", image("png", 200, 200), MediaType.IMAGE_PNG_TYPE);

        assertNotEquals(first, second);
        assertEquals("image/png", http.exchange(HttpRequest.GET(second), byte[].class).getContentType().orElseThrow().toString());
    }

    @Test
    void whatIsNotAPictureWeAcceptIsRefused() {
        register("sara");

        // Not the type it says it is.
        assertProblem(HttpStatus.UNPROCESSABLE_ENTITY, "avatar-invalid",
                () -> upload("sara", "this is text".getBytes(), MediaType.IMAGE_JPEG_TYPE));
        // A PNG sent as a JPEG.
        assertProblem(HttpStatus.UNPROCESSABLE_ENTITY, "avatar-invalid", () -> upload("sara", image("png", 128, 128), MediaType.IMAGE_JPEG_TYPE));
        // Too small, too big in pixels, too big in bytes.
        assertProblem(HttpStatus.UNPROCESSABLE_ENTITY, "avatar-invalid", () -> upload("sara", image("jpg", 32, 32), MediaType.IMAGE_JPEG_TYPE));
        assertProblem(HttpStatus.UNPROCESSABLE_ENTITY, "avatar-invalid", () -> upload("sara", image("png", 1100, 1100), MediaType.IMAGE_PNG_TYPE));
        assertProblem(HttpStatus.UNPROCESSABLE_ENTITY, "avatar-invalid", () -> upload("sara", noisy(600, 600), MediaType.IMAGE_PNG_TYPE));
        // Another kind of picture is not offered.
        assertProblem(HttpStatus.UNPROCESSABLE_ENTITY, "avatar-invalid", () -> upload("sara", image("jpg", 128, 128), MediaType.IMAGE_GIF_TYPE));

        assertEquals(HttpStatus.NOT_FOUND, assertThrows(HttpClientResponseException.class,
                () -> http.exchange(HttpRequest.GET(userPath("sara", "/avatar/1")), byte[].class)).getStatus(), "nothing was stored");
    }

    @Test
    void youNeedAProfileAndASignedInUserToUpload() {
        assertProblem(HttpStatus.NOT_FOUND, "user-not-found", () -> upload("nobody", image("jpg", 128, 128), MediaType.IMAGE_JPEG_TYPE));
        HttpClientResponseException anonymous = assertThrows(HttpClientResponseException.class, () -> http.exchange(
                HttpRequest.PUT("/api/v1/users/me/avatar", image("jpg", 128, 128)).contentType(MediaType.IMAGE_JPEG_TYPE)));
        assertEquals(HttpStatus.UNAUTHORIZED, anonymous.getStatus());
    }

    @Test
    void choosingAPictureOfTheAppsDropsTheUploadedOne() {
        register("sara");
        String address = upload("sara", image("jpg", 128, 128), MediaType.IMAGE_JPEG_TYPE);

        Map<String, Object> profile = patch("/api/v1/users/me", "sara", Map.of("avatarUrl", "/avatars/avatar-03.svg"));

        assertEquals("/avatars/avatar-03.svg", profile.get("avatarUrl"));
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(HttpClientResponseException.class,
                () -> http.exchange(HttpRequest.GET(address), byte[].class)).getStatus());
    }

    @Test
    void keepingTheUploadedAddressInAnUpdateKeepsThePicture() {
        register("sara");
        String address = upload("sara", image("jpg", 128, 128), MediaType.IMAGE_JPEG_TYPE);

        patch("/api/v1/users/me", "sara", Map.of("displayName", "Sara", "avatarUrl", address));

        assertEquals(HttpStatus.OK, http.exchange(HttpRequest.GET(address), byte[].class).status());
    }

    @Test
    void deletingTheAccountDeletesThePicture() {
        register("sara");
        String address = upload("sara", image("jpg", 128, 128), MediaType.IMAGE_JPEG_TYPE);

        send(HttpRequest.DELETE("/api/v1/users/me"), "sara");

        assertEquals(HttpStatus.NOT_FOUND, assertThrows(HttpClientResponseException.class,
                () -> http.exchange(HttpRequest.GET(address), byte[].class)).getStatus());
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private String upload(String name, byte[] bytes, MediaType type) {
        Map<String, Object> profile = http.retrieve(HttpRequest.PUT("/api/v1/users/me/avatar", bytes)
                .contentType(type).bearerAuth(token(name)), MAP);
        return (String) profile.get("avatarUrl");
    }

    private static byte[] image(String format, int width, int height) {
        BufferedImage picture = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                picture.setRGB(x, y, (x * 255 / width) << 16 | (y * 255 / height) << 8 | 128);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(picture, format, out);
        return out.toByteArray();
    }

    private static void write(BufferedImage picture, String format, ByteArrayOutputStream out) {
        try {
            ImageIO.write(picture, format, out);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** A picture that cannot be compressed, so it is larger than the limit in bytes. */
    private static byte[] noisy(int width, int height) {
        java.util.Random random = new java.util.Random(7);
        BufferedImage picture = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                picture.setRGB(x, y, random.nextInt(0xFFFFFF));
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(picture, "png", out);
        return out.toByteArray();
    }

    /** Pretends the last username change was months ago. */
    private void longAgo(String name) {
        transactions.executeWrite(status -> {
            try (PreparedStatement statement = status.getConnection().prepareStatement(
                    "UPDATE user_service.users SET username_changed_at = NOW() - INTERVAL '90 days' WHERE id = ?")) {
                statement.setObject(1, id(name));
                return statement.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }
}
