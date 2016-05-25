package com.conveyal.taui.controllers;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.api.ApiMain;
import com.conveyal.gtfs.api.models.FeedSource;
import com.conveyal.gtfs.model.Stop;
import com.conveyal.taui.AnalystConfig;
import com.conveyal.taui.TransportAnalyst;
import com.conveyal.taui.models.Bundle;
import com.conveyal.taui.persistence.Persistence;
import com.conveyal.taui.util.JsonUtil;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import com.mongodb.util.JSON;
import gnu.trove.list.TDoubleList;
import gnu.trove.list.array.TDoubleArrayList;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileItemFactory;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static spark.Spark.delete;
import static spark.Spark.get;
import static spark.Spark.halt;
import static spark.Spark.post;

/**
 * Created by matthewc on 3/14/16.
 */
public class BundleController {
    private static final Logger LOG = LoggerFactory.getLogger(BundleController.class);

    private static final AmazonS3 s3 = new AmazonS3Client();

    public static Bundle create (Request req, Response res) throws Exception {
        ServletFileUpload sfu = new ServletFileUpload(fileItemFactory);
        Map<String, List<FileItem>> files = sfu.parseParameterMap(req.raw());

        String bundleId = UUID.randomUUID().toString().replace("-", "");

        // cache bundle on disk to avoid OOME
        File bundleFile = File.createTempFile(bundleId, ".zip");

        ObjectMetadata om = new ObjectMetadata();
        om.setContentType("application/zip");

        ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(bundleFile)));

        List<File> localFiles = new ArrayList<>();
        File directory = Files.createTempDir();

        Set<String> usedFileNames = new HashSet<>();

        for (FileItem fi : files.get("files")) {
            // create a unique, safe file name
            String baseName = fi.getName().replace(".zip", "").replaceAll("[^a-zA-Z0-9]", "-");
            String fname = baseName;

            int i = 0;
            while (usedFileNames.contains(fname)) {
                fname = String.format(Locale.US, "%s_%s", fname, i++);

                if (i > 100) {
                    fname = UUID.randomUUID().toString();
                    break;
                }
            }

            usedFileNames.add(fname);

            fname += ".zip";

            ZipEntry ze = new ZipEntry(fname);

            zos.putNextEntry(ze);
            InputStream is = fi.getInputStream();
            ByteStreams.copy(is, zos);
            is.close();

            File localFile = new File(directory, fname);
            fi.write(localFile);
            localFiles.add(localFile);

            zos.closeEntry();
        }

        zos.flush();
        zos.close();

        // upload to s3. OK to do this synchronously as this runs in production on AWS
        // (or on heroku, which runs on AWS), so throughput to S3 is very high.
        s3.putObject(AnalystConfig.bundleBucket, bundleId + ".zip", bundleFile);

        // don't run out of disk space
        bundleFile.delete();

        // create the bundle
        Bundle bundle = new Bundle();
        bundle.id = bundleId;
        bundle.name = files.get("Name").get(0).getString("UTF-8");
        bundle.projectId = files.get("projectId").get(0).getString("UTF-8");

        bundle.group = (String) req.attribute("group");

        bundle.status = Bundle.Status.PROCESSING_GTFS;

        // don't save bundle to database here, because we may block save bel
        //Persistence.bundles.put(bundleId, bundle);

        // make a protective copy to avoid potential issues with modify objects that are in the process
        // of being saved to mongodb
        final Bundle finalBundle = bundle.clone();

        // process async
        new Thread(() -> {
            TDoubleList lats = new TDoubleArrayList();
            TDoubleList lons = new TDoubleArrayList();

            finalBundle.feeds = new ArrayList<>();

            Map<String, String> fileNames = new HashMap<>();
            Map<String, GTFSFeed> feeds = localFiles.stream()
                    .map(file -> {
                        GTFSFeed feed = GTFSFeed.fromFile(file.getAbsolutePath());
                        fileNames.put(feed.feedId, file.getName());
                        return feed;
                    })
                    .collect(Collectors.toMap(f -> f.feedId, f -> f));

            // Make sure it doesn't already contain the feed ID
            if (feeds.keySet().stream().anyMatch(feedId -> ApiMain.feedSources.containsKey(feedId))) {
                LOG.info("Attempt to upload feeds with duplicate IDs");
                return; // don't make duplicate feeds, #3.
                // TODO report error to client
            }

            for (GTFSFeed feed : feeds.values()) {
                Bundle.FeedSummary fs = new Bundle.FeedSummary(feed);
                fs.fileName = fileNames.get(feed.feedId);
                finalBundle.feeds.add(fs);

                // we've already checked for conflicts
                ApiMain.feedSources.put(feed.feedId, new FeedSource(feed));

                // calculate median
                // technically we should not use stops that have no stop times, but
                // that requires parsing a much larger table. We're using a median so we're
                // pretty robust to stops near null island, etc.
                for (Stop stop : feed.stops.values()) {
                    lats.add(stop.stop_lat);
                    lons.add(stop.stop_lon);
                }
            }

            // find the median stop location
            // use a median because it is robust to outliers
            lats.sort();
            lons.sort();
            // not a true median as we don't handle the case when there is an even number of stops
            // and we are supposed to average the two middle value, but close enough
            finalBundle.centerLat = lats.get(lats.size() / 2);
            finalBundle.centerLon = lons.get(lons.size() / 2);
            finalBundle.status = Bundle.Status.DONE;

            Persistence.bundles.put(bundleId, finalBundle);

            directory.delete();
        }).start();

        return bundle;
    }

    public static Bundle deleteBundle (Request req, Response res) {
        Bundle bundle = Persistence.bundles.get(req.params("id"));
        if (bundle == null || !req.attribute("group").equals(bundle.group)) {
            halt(404);
        }

        Persistence.bundles.remove(bundle.id);

        // free memory
        ApiMain.feedSources.remove(bundle.id);

        // remove from s3
        s3.deleteObject(AnalystConfig.bundleBucket, bundle.id + ".zip");

        return bundle;
    }

    public static Object getBundles (Request req, Response res) {
        String group = req.attribute("group");

        if (req.params("id") != null) {
            String id = req.params("id");

            Bundle bundle = Persistence.bundles.get(id);

            if (bundle == null || !group.equals(bundle.group)) halt(404);
            else return bundle;
        }
        else {
            return Persistence.bundles.values().stream()
                    .filter(b -> group.equals(b.group))
                    .collect(Collectors.toList());
        }

        return null;
    }

    public static void register () {
        get("/api/bundle/:id", BundleController::getBundles, JsonUtil.objectMapper::writeValueAsString);
        get("/api/bundle", BundleController::getBundles, JsonUtil.objectMapper::writeValueAsString);
        post("/api/bundle", BundleController::create, JsonUtil.objectMapper::writeValueAsString);
        delete("/api/bundle/:id", BundleController::deleteBundle, JsonUtil.objectMapper::writeValueAsString);
    }

    private static FileItemFactory fileItemFactory = new DiskFileItemFactory();
}
