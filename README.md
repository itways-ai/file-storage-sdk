# file-storage-sdk

A small Spring library that stores files in Cloudflare R2 (S3-compatible
object storage) and returns their public URLs. Today it is used by
auth-service for profile pictures.

- Group / artifact: `com.itways.assistant:file-storage-sdk`
- Version: `2.0.1` (2.x uses AWS SDK for Java v2; 1.x used the end-of-support v1)
- Java 21, Spring Boot 3.2 parent
- Depends only on what the code uses: `spring-boot-autoconfigure`, `spring-web`
  (for `MultipartFile`), `jakarta.annotation-api`, `slf4j-api` and the AWS S3
  client. The consuming service brings its own web server, Jackson and logging
  backend (2.0.0 pulled in all of `spring-boot-starter-web`).

## Use it

Add the dependency, set the `cloudflare.r2.*` properties (below) and inject
`AttachmentService`. `UploadAutoConfiguration` is a Spring Boot
auto-configuration (`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`),
so the jar on the classpath is enough. `@EnableAttachment` on the application
class still works and gives the same single set of beans. (Up to 2.0.0 the
imports file sat under `spring/` without `META-INF/`, so only `@EnableAttachment`
wired anything.)

The configuration applies only when both `cloudflare.r2.access-key` and
`cloudflare.r2.secret-key` are set, so an application that has the jar but does
not configure R2 still starts (without an `AttachmentService`).

```java
UploadResponse r = attachmentService.upload("avatars/" + accountId, "avatar.png", bytes);
r.getUrl();   // https://<public host>/avatars/<accountId>/<uuid>-avatar.png, store this
r.getKey();   // avatars/<accountId>/<uuid>-avatar.png, pass it to delete()
attachmentService.delete(r.getKey());
```

| Method | What it does |
|---|---|
| `upload(keyPrefix, fileName, bytes)` | Stores the bytes under a new unique key; returns URL and key. Rejects empty content and content over 50 MB (`IllegalArgumentException`). |
| `upload(fileName, bytes)` | Same, with the configured default prefix (`cloudflare.r2.key-prefix`, blank means the bucket root). |
| `delete(key)` | Deletes one object. A key that does not exist still succeeds, as in S3. |

Storage errors are thrown as `AttachmentStorageException` (a `RuntimeException`).
There is no read method: objects are served straight from the public host.

## Object keys

```
[<keyPrefix>/]<uuid>-<sanitised file name>
```

- The random UUID makes every key unique, so an upload can never overwrite an
  existing object, whatever name the caller passes, and URLs cannot be guessed.
- The file name keeps `[A-Za-z0-9_-]` and its extension; anything else becomes
  `_`. Names longer than 128 characters are cut, keeping the extension.
- Each `/`-separated prefix segment is sanitised the same way and empty
  segments are dropped, so a prefix can never contain `..`.
- The content type comes from the extension: png, jpg/jpeg, gif, webp, pdf,
  txt and json are fixed; other extensions are probed from the host's MIME
  database and fall back to `application/octet-stream`.

The public URL is `<public base>/<key>`, where the base is `public-base-url`
if set, otherwise `public-domain` (`https://` is added when it has no scheme).
A trailing `/` on the base is ignored.

## Configuration

All under `cloudflare.r2.*`. The consuming service maps them to environment
variables; auth-service uses the names below. Never commit real values.

| Property | Environment variable (auth-service) | Required | Notes |
|---|---|---|---|
| `account-id` | `CLOUDFLARE_R2_ACCOUNT_ID` | yes | Builds the endpoint `https://<account-id>.r2.cloudflarestorage.com`. |
| `access-key` | `CLOUDFLARE_R2_ACCESS_KEY` | yes | R2 API token access key id. |
| `secret-key` | `CLOUDFLARE_R2_SECRET_KEY` | yes | R2 API token secret. |
| `bucket` | `CLOUDFLARE_R2_BUCKET` | yes | |
| `public-domain` | `CLOUDFLARE_R2_PUBLIC_DOMAIN` | one of these two | Public host of the bucket (custom domain or `r2.dev`). |
| `public-base-url` | none | one of these two | Takes precedence over `public-domain`; may include a path. |
| `key-prefix` | none | no | Default prefix for `upload(fileName, bytes)`. |
| `endpoint` | none | no | Overrides the derived R2 endpoint. For tests and S3-compatible stand-ins only. |

With no real credentials, auth-service uses the stand-in `r2disabled` for the
three credential variables so the service still starts; uploads then fail
with a storage error (auth-service answers 503).

## R2 client settings

The S3 client (bean `fileStorageS3Client`) is built in
`UploadAutoConfiguration.buildS3Client` with:

- endpoint override as above, region `auto` (R2 ignores it; SigV4 needs one);
- path-style addressing (`https://<endpoint>/<bucket>/<key>`);
- `requestChecksumCalculation` and `responseChecksumValidation` set to
  `WHEN_REQUIRED`. Since SDK 2.30 the default adds CRC checksums, sent as
  aws-chunked trailers, to every upload, which R2 has rejected or
  mishandled;
- chunked encoding off, so every upload is one plain PUT body with a
  `Content-Length`, as with the 1.x client;
- the JDK `HttpURLConnection` transport (`url-connection-client`). The SDK's
  default Apache 5 client needs httpclient5 5.4 or later, but Spring Boot's
  dependency management (here and in consuming services) pins an older
  version, which fails at runtime with `NoClassDefFoundError`.

## Build

The host JDK may be too new for Lombok; build in the Maven container:

```bash
docker run --rm -v "$PWD":/src -v "$HOME/.m2":/root/.m2 -w /src \
  maven:3.9-eclipse-temurin-21 mvn -B clean install
```

`docker/java/Dockerfile` installs this library, from the workspace, before
building the services.

## Tests

`mvn clean install` runs the unit tests. They need no Docker and no network:
`StubS3Server` is a small S3-compatible HTTP server on 127.0.0.1 that
records requests. They drive the real client, built as in production, and cover:

- key shape, uniqueness (the same name twice gives two keys) and sanitising
  of prefix and name;
- content type per extension;
- no checksum headers, no aws-chunked body, region `auto` in the signature;
- public URL shape for `public-domain`, bare domain and `public-base-url`;
- delete by key, input validation, storage errors mapped to
  `AttachmentStorageException`;
- `@EnableAttachment` wiring from `cloudflare.r2.*` properties;
- the auto-configuration: listed in the imports file, working without
  `@EnableAttachment`, one set of beans with it, and backing off without
  credentials (`UploadAutoConfigurationTest`).

No test calls Cloudflare.
