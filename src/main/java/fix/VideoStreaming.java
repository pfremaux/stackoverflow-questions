package fix;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Trying to follow RFC: https://datatracker.ietf.org/doc/html/rfc9110#name-content-range
 */

class VideoStreaming {
    private static final File VIDEO_FILE = new File("E:\\dev\\intellij\\webserver-lib\\src\\main\\web\\Puddle Of Mudd - Blurry.mp4");//"#Please add a mp4 file here.");
    public static final int LISTENING_PORT = 8181;
    private final String indexHtmlPage;
    private final int MAX_BYTES_TO_RETURN = 512_000;

    public VideoStreaming() throws IOException {
        this.indexHtmlPage = Files.readString(Path.of("./index.html"));
    }

    public void start() throws IOException {
        final HttpServer httpServer = HttpServer.create(new InetSocketAddress(LISTENING_PORT), 0);

        // Create endpoint to stream a video
        // index.html will call: http://127.0.0.1:8181/watch
        final HttpHandler videoStreamHandler = exchange -> streamVideo(exchange, VIDEO_FILE);
        httpServer.createContext("/watch", videoStreamHandler);

        // Create endpoint to return index.html
        // Thanks to it, just call: http://127.0.0.1:8181/index
        final HttpHandler indexPageHandler = exchange -> {
            try {
                final byte[] bytes = indexHtmlPage.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                try (final OutputStream responseBody = exchange.getResponseBody()) {
                    responseBody.write(bytes);
                }
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        };
        httpServer.createContext("/index", indexPageHandler);
        httpServer.start();
        System.out.printf("Visit: http://127.0.0.1:%d/index", LISTENING_PORT);
    }

    private void streamVideo(HttpExchange exchange, File file) {
        try (final FileInputStream videoStream = new FileInputStream(file)) {
            // Determine the range requested by the client
            final Range streamRange = getRange(exchange, file.length());

            // Jumps to the chunk requested by the client.
            videoStream.skip(streamRange.start);

            final int requestedChunkSize = (int) streamRange.requestedChunkLength();
            System.out.println("[Backend] chunk size requested: " + requestedChunkSize);

            // We're defining a ceiling just to make sure we're not handling a huge file.
            final int effectiveByteCountsToRead = Math.min(requestedChunkSize, MAX_BYTES_TO_RETURN);
            // Set the response headers based on the requested range
            prepareResponseHeader(exchange, streamRange, effectiveByteCountsToRead);
            sendVideoStream(exchange, videoStream, streamRange, effectiveByteCountsToRead);
        } catch (Throwable t) {
            // Catching here because httpServer hides exceptions.
            t.printStackTrace();
        }
    }

    private static void sendVideoStream(HttpExchange exchange, FileInputStream videoStream, Range streamRange, int effectiveByteCountsToRead) {
        try (OutputStream outputStream = exchange.getResponseBody()) {
            // HTTP 206: partial content
            // HTTP 200: final part.
            exchange.sendResponseHeaders(streamRange.isFinalRange() ? 200 : 206, effectiveByteCountsToRead);

            final byte[] buffer = new byte[effectiveByteCountsToRead];
            int remainingBytesToRead = effectiveByteCountsToRead;
            int totalBytesRead = 0;
            while (remainingBytesToRead > 0) {
                totalBytesRead = transferFileChunkToOutputStream(videoStream, effectiveByteCountsToRead, outputStream, totalBytesRead, remainingBytesToRead, buffer);
                remainingBytesToRead = remainingBytesToRead - totalBytesRead;
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static int transferFileChunkToOutputStream(FileInputStream mediaFileInputStream, int requestedChunkSize, OutputStream outputStream, int totalBytesRead, int remainingBytesToRead, byte[] buffer) throws IOException {
        final int bytesRead = mediaFileInputStream.read(buffer, 0, Math.min(remainingBytesToRead, buffer.length));
        System.out.println("[Backend] Bytes read in file: " + bytesRead);
        outputStream.write(buffer, 0, bytesRead);
        totalBytesRead += bytesRead;
        if (totalBytesRead > requestedChunkSize) {
            System.err.println("[ERROR] More bytes have been read then requested: totalBytesRead=" + totalBytesRead + " ; requestedChunkSize=" + requestedChunkSize);
        }
        return totalBytesRead;
    }

    private static void prepareResponseHeader(HttpExchange exchange, Range range, int effectiveByteCountsToRead) {
        final Headers responseHeaders = exchange.getResponseHeaders();
        responseHeaders.add("Accept-Ranges", "bytes");
        responseHeaders.add("Content-Type", "video/mp4");
        responseHeaders.add("Content-Length", String.valueOf(effectiveByteCountsToRead));
        final String headerValue = range.getHeaderValue();
        responseHeaders.add("Content-Range", headerValue);
        System.out.println("[Response header] Content-Range: " + headerValue);
    }

    private Range getRange(HttpExchange exchange, long videoLength) {
        final List<String> rangeHeader = exchange.getRequestHeaders().get("Range");
        // Expects format: bytes=<firstPos>-<lastPos> OR bytes=<firstPos>-
        // Other case like bytes=-<lastPos> OR bytes=<firstPos>-<lastPos>,<firstPos>-<lastPos> aren't supported
        final String range = rangeHeader.get(0);
        System.out.println("[Request header] Range: " + range);
        if (range != null && range.startsWith("bytes=")) {
            final String[] rangeValues = range.substring("bytes=".length()).split("-");
            final long firstPos = Long.parseLong(rangeValues[0]);
            long lastPos;
            // If client sent a last-pos
            if (rangeValues.length > 1 && rangeValues[1].length() > 0) {
                lastPos = Math.min(videoLength - 1, Long.parseLong(rangeValues[1]))/* + 1*/;
            } else {
                lastPos = videoLength - 1;
            }
            if (lastPos - firstPos > MAX_BYTES_TO_RETURN) {
                lastPos = Math.min(videoLength - 1, firstPos + MAX_BYTES_TO_RETURN);
            }
            return new Range(firstPos, lastPos, videoLength);
        } else {
            return new Range(0, videoLength - 1, videoLength);
        }
    }

    private record Range(long start, long end, long fileLength) {
        String getHeaderValue() {
            return "bytes " + start + "-" + (end) + "/" + (fileLength);
        }

        boolean isFinalRange() {
            return (end) == fileLength;
        }

        long requestedChunkLength() {
            return (end + 1) - start;
        }
    }
}

