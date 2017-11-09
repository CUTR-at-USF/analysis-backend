package com.conveyal.taui.controllers;

import com.amazonaws.HttpMethod;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.conveyal.r5.analyst.BootstrapPercentileMethodHypothesisTestGridReducer;
import com.conveyal.r5.analyst.Grid;
import com.conveyal.r5.analyst.SelectingGridReducer;
import com.conveyal.taui.AnalysisServerConfig;
import com.conveyal.taui.AnalysisServerException;
import com.conveyal.taui.analysis.RegionalAnalysisManager;
import com.conveyal.taui.models.Project;
import com.conveyal.taui.models.RegionalAnalysis;
import com.conveyal.taui.persistence.Persistence;
import com.conveyal.taui.persistence.TiledAccessGrid;
import com.conveyal.taui.util.JsonUtil;
import com.conveyal.taui.util.WrappedURL;
import com.mongodb.QueryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.URL;
import java.util.Collection;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPOutputStream;

import static java.lang.Boolean.parseBoolean;
import static spark.Spark.delete;
import static spark.Spark.get;
import static spark.Spark.post;

/**
 * Created by matthewc on 10/21/16.
 */
public class RegionalAnalysisController {
    private static final Logger LOG = LoggerFactory.getLogger(RegionalAnalysisController.class);

    public static AmazonS3 s3 = new AmazonS3Client();

    /** use a single thread executor so that the writing thread does not die before the S3 upload is finished */
    private static final ExecutorService executorService = Executors.newSingleThreadExecutor();

    /** How long request URLs are good for */
    public static final int REQUEST_TIMEOUT_MSEC = 15 * 1000;

    public static Collection<RegionalAnalysis> getRegionalAnalysis (Request req, Response res) {
        return Persistence.regionalAnalyses.findPermitted(
                QueryBuilder.start().and(
                        QueryBuilder.start("projectId").is(req.params("projectId")).get(),
                        QueryBuilder.start("deleted").is(false).get()
                ).get(),
                req.attribute("accessGroup")
        );
    }

    public static RegionalAnalysis deleteRegionalAnalysis (Request req, Response res) {
        String accessGroup = req.attribute("accessGroup");
        String email = req.attribute("email");

        RegionalAnalysis analysis = Persistence.regionalAnalyses.findByIdFromRequestIfPermitted(req);
        analysis.deleted = true;
        Persistence.regionalAnalyses.updateByUserIfPermitted(analysis, email, accessGroup);

        // clear it from the broker
        if (!analysis.complete) {
            RegionalAnalysisManager.deleteJob(analysis._id);
        }

        return analysis;
    }

    private static void validateFormat(String format) {
        if (!"grid".equals(format) && !"png".equals(format) && !"tiff".equals(format)) {
            throw AnalysisServerException.BadRequest("Format \"" + format + "\" is invalid. Request format must be \"grid\", \"png\", or \"tiff\".");
        }
    }

    /** Get a particular percentile of a query as a grid file */
    public static Object getPercentile (Request req, Response res) throws IOException {
        String regionalAnalysisId = req.params("_id");
        RegionalAnalysis analysis = Persistence.regionalAnalyses.findByIdFromRequestIfPermitted(req);

        // while we can do non-integer percentiles, don't allow that here to prevent cache misses
        String format = req.params("format").toLowerCase();
        validateFormat(format);

        String percentileGridKey;

        String redirectText = req.queryParams("redirect");
        boolean redirect;
        if (redirectText == null || "" .equals(redirectText)) redirect = true;
        else redirect = parseBoolean(redirectText);


        if (analysis.travelTimePercentile == -1) {
            // Andrew Owen style average instantaneous accessibility
            percentileGridKey = String.format("%s_average.%s", regionalAnalysisId, format);
        } else {
            // accessibility given X percentile travel time
            // use the point estimate when there are many bootstrap replications of the accessibility given median
            // accessibility
            // no need to record what the percentile is, that is fixed by the regional analysis.
            percentileGridKey = String.format("%s_given_percentile_travel_time.%s", regionalAnalysisId, format);
        }

        String accessGridKey = String.format("%s.access", regionalAnalysisId);

        if (!s3.doesObjectExist(AnalysisServerConfig.resultsBucket, percentileGridKey)) {
            // make the grid
            Grid grid;
            long computeStart = System.currentTimeMillis();
            if (analysis.travelTimePercentile == -1) {
                // Andrew Owen style average instantaneous accessibility
                // The samples stored in the access grid are samples of instantaneous accessibility at different minutes
                // and Monte Carlo draws, average them together
                throw new IllegalArgumentException("Old-style instantaneous-accessibility regional analyses are no longer supported");
            } else {
                // This is accessibility given x percentile travel time, the first sample is the point estimate
                // computed using all monte carlo draws, and subsequent samples are bootstrap replications. Return the
                // point estimate in the grids.
                LOG.info("Point estimate for regional analysis {} not found, building it", regionalAnalysisId);
                grid = new SelectingGridReducer(0).compute(AnalysisServerConfig.resultsBucket, accessGridKey);
            }
            LOG.info("Building grid took {}s", (System.currentTimeMillis() - computeStart) / 1000d);

            PipedInputStream pis = new PipedInputStream();
            PipedOutputStream pos = new PipedOutputStream(pis);

            ObjectMetadata om = new ObjectMetadata();

            if ("grid".equals(format)) {
                om.setContentType("application/octet-stream");
                om.setContentEncoding("gzip");
            } else if ("png".equals(format)) {
                om.setContentType("image/png");
            } else if ("tiff".equals(format)) {
                om.setContentType("image/tiff");
            }

            executorService.execute(() -> {
                try {
                    if ("grid".equals(format)) {
                        grid.write(new GZIPOutputStream(pos));
                    } else if ("png".equals("format")) {
                        grid.writePng(pos);
                    } else if ("tiff".equals(format)) {
                        grid.writeGeotiff(pos);
                    }
                } catch (IOException e) {
                    LOG.info("Error writing percentile to S3", e);
                }
            });

            // not using S3Util.streamToS3 because we need to make sure the put completes before we return
            // the URL, as the client will go to it immediately.
            s3.putObject(AnalysisServerConfig.resultsBucket, percentileGridKey, pis, om);
        }

        Date expiration = new Date();
        expiration.setTime(expiration.getTime() + REQUEST_TIMEOUT_MSEC);

        GeneratePresignedUrlRequest presigned = new GeneratePresignedUrlRequest(AnalysisServerConfig.resultsBucket, percentileGridKey);
        presigned.setExpiration(expiration);
        presigned.setMethod(HttpMethod.GET);
        URL url = s3.generatePresignedUrl(presigned);

        if (redirect) {
            res.type("text/plain"); // override application/json
            res.redirect(url.toString());
            res.status(302); // temporary redirect, this URL will soon expire
            return null;
        } else {
            return new WrappedURL(url.toString());
        }
    }

    /** Get a probability of improvement from a baseline to a scenario */
    public static Object getProbabilitySurface (Request req, Response res) throws IOException {
        String base = req.params("baseId");
        String scenario = req.params("scenarioId");
        String format = req.params("format").toLowerCase();
        validateFormat(format);

        String redirectText = req.queryParams("redirect");
        boolean redirect;
        if (redirectText == null || "" .equals(redirectText)) redirect = true;
        else redirect = parseBoolean(redirectText);

        String probabilitySurfaceKey = String.format("%s_%s_probability.%s", base, scenario, format);

        if (!s3.doesObjectExist(AnalysisServerConfig.resultsBucket, probabilitySurfaceKey)) {
            LOG.info("Probability surface for {} -> {} not found, building it", base, scenario);

            String baseKey = String.format("%s.access", base);
            String scenarioKey = String.format("%s.access", scenario);

            // if these are bootstrapped travel times with a particular travel time percentile, use the bootstrap
            // p-value/hypothesis test computer. Otherwise use the older setup.
            // TODO should all comparisons use the bootstrap computer? the only real difference is that it is two-tailed.
            BootstrapPercentileMethodHypothesisTestGridReducer computer = new BootstrapPercentileMethodHypothesisTestGridReducer();

            Grid grid = computer.computeImprovementProbability(AnalysisServerConfig.resultsBucket, baseKey, scenarioKey);

            PipedInputStream pis = new PipedInputStream();
            PipedOutputStream pos = new PipedOutputStream(pis);

            ObjectMetadata om = new ObjectMetadata();
            if ("grid".equals(format)) {
                om.setContentType("application/octet-stream");
                om.setContentEncoding("gzip");
            } else if ("png".equals(format)) {
                om.setContentType("image/png");
            } else if ("tiff".equals(format)) {
                om.setContentType("image/tiff");
            }

            executorService.execute(() -> {
                try {
                    if ("grid".equals(format)) {
                        grid.write(new GZIPOutputStream(pos));
                    } else if ("png".equals("format")) {
                        grid.writePng(pos);
                    } else if ("tiff".equals(format)) {
                        grid.writeGeotiff(pos);
                    }
                } catch (IOException e) {
                    LOG.info("Error writing probability surface to S3", e);
                }
            });

            // not using S3Util.streamToS3 because we need to make sure the put completes before we return
            // the URL, as the client will go to it immediately.
            s3.putObject(AnalysisServerConfig.resultsBucket, probabilitySurfaceKey, pis, om);
        }

        Date expiration = new Date();
        expiration.setTime(expiration.getTime() + REQUEST_TIMEOUT_MSEC);

        GeneratePresignedUrlRequest presigned = new GeneratePresignedUrlRequest(AnalysisServerConfig.resultsBucket, probabilitySurfaceKey);
        presigned.setExpiration(expiration);
        presigned.setMethod(HttpMethod.GET);
        URL url = s3.generatePresignedUrl(presigned);

        if (redirect) {
            res.type("text/plain"); // override default application/json
            res.redirect(url.toString());
            res.status(302); // temporary redirect, this URL will soon expire
            return null;
        } else {
            return new WrappedURL(url.toString());
        }
    }

    public static int[] getSamplingDistribution (Request req, Response res) {
        String regionalAnalysisId = req.params("_id");
        double lat = Double.parseDouble(req.params("lat"));
        double lon = Double.parseDouble(req.params("lon"));

        return TiledAccessGrid
                .get(AnalysisServerConfig.resultsBucket,  String.format("%s.access", regionalAnalysisId))
                .getLatLon(lat, lon);
    }

    public static RegionalAnalysis createRegionalAnalysis (Request req, Response res) throws IOException {
        RegionalAnalysis regionalAnalysis = Persistence.regionalAnalyses.createFromJSONRequest(req, RegionalAnalysis.class);
        Project project = Persistence.projects.findByIdIfPermitted(regionalAnalysis.projectId, req.attribute("accessGroup"));

        // TODO coordinate parameters that go to broker/r5
        // this scenario is specific to this job
        regionalAnalysis.request.scenarioId = null;
        regionalAnalysis.request.scenario.id = regionalAnalysis._id;
        regionalAnalysis.creationTime = System.currentTimeMillis();
        regionalAnalysis.zoom = 9;

        // TODO do statuses differently
        if (regionalAnalysis.bounds != null) regionalAnalysis.computeBoundingBoxFromBounds();
        else if (regionalAnalysis.width == 0) regionalAnalysis.computeBoundingBoxFromProject(project);

        Persistence.regionalAnalyses.put(regionalAnalysis);
        RegionalAnalysisManager.enqueue(regionalAnalysis);

        return regionalAnalysis;
    }

    public static void register () {
        get("/api/project/:projectId/regional", RegionalAnalysisController::getRegionalAnalysis, JsonUtil.objectMapper::writeValueAsString);
        get("/api/regional/:_id/grid/:format", RegionalAnalysisController::getPercentile, JsonUtil.objectMapper::writeValueAsString);
        get("/api/regional/:_id/samplingDistribution/:lat/:lon", RegionalAnalysisController::getSamplingDistribution, JsonUtil.objectMapper::writeValueAsString);
        get("/api/regional/:baseId/:scenarioId/:format", RegionalAnalysisController::getProbabilitySurface, JsonUtil.objectMapper::writeValueAsString);
        delete("/api/regional/:_id", RegionalAnalysisController::deleteRegionalAnalysis, JsonUtil.objectMapper::writeValueAsString);
        post("/api/regional", RegionalAnalysisController::createRegionalAnalysis, JsonUtil.objectMapper::writeValueAsString);
    }

}
