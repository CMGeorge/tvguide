/*
 * Copyright (C) 2011  Southern Storm Software, Pty Ltd.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.southernstorm.tvguide;

import java.io.File;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Random;
import java.util.zip.GZIPInputStream;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;

import android.os.AsyncTask;

/**
 * Cache management and network fetching for channel data.
 */
public class TvChannelCache extends ExternalMediaHandler {

    private String serviceName;
    private File httpCacheDir;
    private Random rand;
    private boolean debug;
    private List<TvNetworkListener> listeners;
    private static TvChannelCache instance = null;

    private TvChannelCache() {
        this.serviceName = "";
        this.rand = new Random(System.currentTimeMillis());
        this.listeners = new ArrayList<TvNetworkListener>();
    }

    void setDebug(boolean value) {
        debug = value;
    }

    public static TvChannelCache getInstance() {
        if (instance == null) {
            instance = new TvChannelCache();
            instance.setServiceName("OzTivo");
            instance.setDebug(true);    // FIXME: remove before releasing
        }
        return instance;
    }

    /**
     * Gets the name of the service to cache channel data underneath.
     *
     * @return the service name, or the empty string if no service
     */
    public String getServiceName() {
        return serviceName;
    }

    /**
     * Sets the name of the service to cache channel data underneath.
     *
     * @param serviceName the service name, or the empty string if no service
     */
    public void setServiceName(String serviceName) {
        if (serviceName == null)
            serviceName = "";
        if (!this.serviceName.equals(serviceName)) {
            this.serviceName = serviceName;
            if (isMediaUsable())
                reloadService();
        }
    }

    /**
     * Open the XMLTV data file in the cache for a specific channel and date.
     *
     * The data on the SD card is stored as gzip'ed XML.  The stream returned
     * by this function will unzip the data as it is read.
     *
     * @param channel the channel
     * @param date the date corresponding to the requested data
     * @return an input stream, or null if the data is not present
     */
    public InputStream openChannelData(TvChannel channel, Calendar date) {
        File file = dataFile(channel, date, ".xml.gz");
        if (file == null || !file.exists())
            return null;
        try {
            FileInputStream fileStream = new FileInputStream(file);
            try {
                return new GZIPInputStream(fileStream);
            } catch (IOException e) {
                fileStream.close();
                return null;
            }
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Determine if data for a specific channel and date is available in the cache
     * 
     * @param channel the channel
     * @param date the date to look for
     * @return true if data is available, false if not
     */
    public boolean hasChannelData(TvChannel channel, Calendar date) {
        File file = dataFile(channel, date, ".xml.gz");
        if (file == null || !file.exists())
            return false;
        else
            return true;
    }
    
    /**
     * Expire old entries in the cache.
     */
    public void expire() {
        if (httpCacheDir == null)
            return;
        String[] entries = httpCacheDir.list();
        GregorianCalendar today = new GregorianCalendar();
        int todayYear = today.get(Calendar.YEAR);
        int todayMonth = today.get(Calendar.MONTH) + 1;
        int todayDay = today.get(Calendar.DAY_OF_MONTH);
        for (int index = 0; index < entries.length; ++index) {
            // Look for files that end in ".xml.gz" or ".cache".
            String name = entries[index];
            int suffixLength;
            if (name.endsWith(".xml.gz"))
                suffixLength = 7;
            else if (name.endsWith(".cache"))
                suffixLength = 6;
            else
                continue;
            if ((name.length() - suffixLength) < 10)
                continue;

            // Extract the date in the format YYYY-MM-DD from the name
            // and determine if it is less than today.
            int posn = name.length() - suffixLength - 10;
            int year = Utils.parseField(name, posn, 4);
            int month = Utils.parseField(name, posn + 5, 2);
            int day = Utils.parseField(name, posn + 8, 2);
            if (year > todayYear)
                continue;
            if (year == todayYear) {
                if (month > todayMonth)
                    continue;
                if (month == todayMonth) {
                    if (day >= todayDay)
                        continue;
                }
            }

            // Delete the file as it is older than today.
            File file = new File(httpCacheDir, name);
            if (debug)
                System.out.println("expiring " + file.getPath());
            file.delete();
        }
    }
    
    /**
     * Clear the entire contents of the cache.
     */
    public void clear() {
        if (httpCacheDir == null)
            return;
        String[] entries = httpCacheDir.list();
        for (int index = 0; index < entries.length; ++index) {
            // Look for files that end in ".xml.gz" or ".cache".
            String name = entries[index];
            int suffixLength;
            if (name.endsWith(".xml.gz"))
                suffixLength = 7;
            else if (name.endsWith(".cache"))
                suffixLength = 6;
            else
                continue;
            if ((name.length() - suffixLength) < 10)
                continue;

            // Delete the file.
            File file = new File(httpCacheDir, name);
            if (debug)
                System.out.println("deleting " + file.getPath());
            file.delete();
        }
    }

    /**
     * Reloads the service in response to a service name or media change.
     */
    private void reloadService() {
        // If the service name is empty, then there is no need for a cache.
        if (serviceName.length() == 0) {
            unloadService();
            return;
        }

        // Create the cache directory if it doesn't already exist.
        File cacheDir = getCacheDir();
        if (cacheDir == null) {
            unloadService();
            return;
        }
        File serviceCacheDir = new File(cacheDir, serviceName);
        httpCacheDir = new File(serviceCacheDir, "http");
        httpCacheDir.mkdirs();
        if (!httpCacheDir.exists()) {
            unloadService();
            return;
        }
    }

    /**
     * Unloads the service in response to the media being unmounted, usually
     * because the SD card has been mounted via USB by another computer.
     */
    private void unloadService() {
        httpCacheDir = null;
    }

    @Override
    protected void mediaUsableChanged() {
        if (isMediaUsable())
            reloadService();
        else
            unloadService();
    }

    /**
     * Get the name of the data file corresponding to a particular
     * channel and date.
     *
     * @param channel the channel
     * @param date the date to fetch
     * @param extension the file extension, ".xml.gz" or ".cache"
     * @return the filename encapsulated in a File object, or null if no cache
     */
    private File dataFile(TvChannel channel, Calendar date, String extension) {
        if (httpCacheDir == null)
            return null;
        StringBuilder name = new StringBuilder();
        int year = date.get(Calendar.YEAR);
        int month = date.get(Calendar.MONTH) + 1;
        int day = date.get(Calendar.DAY_OF_MONTH);
        name.append(channel.getId());
        name.append('_');
        name.append((char)('0' + ((year / 1000) % 10)));
        name.append((char)('0' + ((year / 100) % 10)));
        name.append((char)('0' + ((year / 10) % 10)));
        name.append((char)('0' + (year % 10)));
        name.append('-');
        name.append((char)('0' + ((month / 10) % 10)));
        name.append((char)('0' + (month % 10)));
        name.append('-');
        name.append((char)('0' + ((day / 10) % 10)));
        name.append((char)('0' + (day % 10)));
        name.append(extension);
        return new File(httpCacheDir, name.toString());
    }

    private class RequestInfo {
        public TvChannel channel;
        public Calendar date;
        public Calendar primaryDate;
        public URI uri;
        public File cacheFile;
        public File dataFile;
        public String etag;
        public String lastModified;
        public String userAgent;
        public boolean success;
        public boolean notFound;
        public RequestInfo next;

        public void updateFromResponse(HttpResponse response) {
            Header header = response.getFirstHeader("ETag");
            if (header != null)
                etag = header.getValue();
            header = response.getFirstHeader("Last-Modified");
            if (header != null)
                lastModified = header.getValue();
        }
    };

    private RequestInfo requestQueue;
    private TvChannel currentRequestChannel;
    private Calendar currentRequestDate;
    private Calendar currentRequestPrimaryDate;
    private boolean requestsActive;

    /**
     * Background asynchronous task for downloading data from the Internet.
     */
    private class DownloadAsyncTask extends AsyncTask<RequestInfo, Integer, RequestInfo> {
        private boolean fetch(RequestInfo info) {
            try {
                DefaultHttpClient client = new DefaultHttpClient();
                HttpGet request = new HttpGet(info.uri);
                request.setHeader("User-Agent", info.userAgent);
                if (info.etag != null)
                    request.setHeader("If-None-Match", info.etag);
                if (info.lastModified != null)
                    request.setHeader("If-Modified-Since", info.lastModified);
                request.setHeader("Accept-Encoding", "gzip");
                HttpResponse response = client.execute(request);
                int status = response.getStatusLine().getStatusCode();
                if (status == HttpStatus.SC_OK) {
                    // Successful response with new data.  Copy it to the cache.
                    info.updateFromResponse(response);
                    HttpEntity entity = response.getEntity();
                    InputStream content = entity.getContent();
                    try {
                        FileOutputStream output = new FileOutputStream(info.dataFile);
                        byte[] buffer = new byte [2048];
                        try {
                            int length;
                            while ((length = content.read(buffer, 0, 2048)) > 0)
                                output.write(buffer, 0, length);
                        } finally {
                            output.close();
                        }
                    } catch (IOException e) {
                        return false;
                    } finally {
                        content.close();
                    }
                    return true;
                } else if (status == HttpStatus.SC_NOT_MODIFIED) {
                    // Data has not changed since the last request.
                    info.updateFromResponse(response);
                    return true;
                } else if (status == HttpStatus.SC_NOT_FOUND) {
                    // Explicit 404 Not Found from the server.
                    info.notFound = true;
                    return false;
                } else {
                    // Request failed for some other reason.
                    return false;
                }
            } catch (UnsupportedEncodingException e) {
                return false;
            } catch (MalformedURLException e) {
                return false;
            } catch (IOException e) {
                return false;
            }
        }

        protected RequestInfo doInBackground(RequestInfo... requests) {
            RequestInfo info = requests[0];
            // TODO: read ETag/Last-Modified data from ".cache" file.
            info.success = fetch(info);
            if (!info.success) {
                // Something failed during the request - delete the cache files
                // before handing the result back to the main thread.
                info.cacheFile.delete();
                info.dataFile.delete();
            }
            // TODO: write ETag/Last-Modified data to ".cache" file.
            return info;
        }

        protected void onProgressUpdate(Integer... progress) {
            // Progress reporting not used by this task.
        }

        protected void onPostExecute(RequestInfo info) {
            if (debug) {
                if (info.success)
                    System.out.println("fetched to " + info.dataFile.getPath());
                else
                    System.out.println("fetch failed");
            }
            reportRequestResult(info);
            startNextRequest();
        }
    };

    /**
     * Fetches the guide data for a specific date and time.
     * 
     * @param channel the channel
     * @param date the date to request
     */
    public void fetch(TvChannel channel, Calendar date) {
        fetch(channel, date, date);
    }

    /**
     * Fetches the guide data for a specific date and time, as part of a multi-day request.
     * At least two days worth of data are needed to show 6:00am one day to 6:00am the next.
     * The first day is the "primary" and typically must be fetched from the server.
     * The second day's data is optional and an error will not be reported to the user
     * if it is not available.
     * 
     * @param channel the channel
     * @param date the date to request
     * @param primaryDate the primary date for multi-day requests
     */
    public void fetch(TvChannel channel, Calendar date, Calendar primaryDate) {
        // Bail out if the cache is unusable.
        if (httpCacheDir == null)
            return;

        // Determine the base URL to use.  OzTivo rules specify that a
        // url should be chosen randomly from the list of base urls.
        // http://www.oztivo.net/twiki/bin/view/TVGuide/StaticXMLGuideAPI
        List<String> baseUrls = channel.getBaseUrls();
        if (baseUrls.isEmpty())
            return;
        String baseUrl;
        if (baseUrls.size() >= 2)
            baseUrl = baseUrls.get(rand.nextInt(baseUrls.size()));
        else
            baseUrl = baseUrls.get(0);

        // Generate the URI for the request.
        StringBuilder requestUrl = new StringBuilder();
        int year = date.get(Calendar.YEAR);
        int month = date.get(Calendar.MONTH) + 1;
        int day = date.get(Calendar.DAY_OF_MONTH);
        requestUrl.append(baseUrl);
        if (!baseUrl.endsWith("/"))
            requestUrl.append('/');
        requestUrl.append(channel.getId());
        requestUrl.append('_');
        requestUrl.append((char)('0' + ((year / 1000) % 10)));
        requestUrl.append((char)('0' + ((year / 100) % 10)));
        requestUrl.append((char)('0' + ((year / 10) % 10)));
        requestUrl.append((char)('0' + (year % 10)));
        requestUrl.append('-');
        requestUrl.append((char)('0' + ((month / 10) % 10)));
        requestUrl.append((char)('0' + (month % 10)));
        requestUrl.append('-');
        requestUrl.append((char)('0' + ((day / 10) % 10)));
        requestUrl.append((char)('0' + (day % 10)));
        requestUrl.append(".xml.gz");
        URI uri;
        try {
            uri = new URI(requestUrl.toString());
        } catch (URISyntaxException e) {
            return;
        }

        // Create the request info block.
        RequestInfo info = new RequestInfo();
        info.channel  = channel;
        info.date = date;
        info.primaryDate = primaryDate;
        info.uri = uri;
        info.cacheFile = dataFile(channel, date, ".cache");
        info.dataFile = dataFile(channel, date, ".xml.gz");
        info.etag = null;
        info.lastModified = null;
        info.userAgent = getContext().getResources().getString(R.string.user_agent);
        info.success = false;

        // Queue up the request to fetch the data from the network.
        addRequestToQueue(info);
    }

    /**
     * Adds a request to the queue of files to be downloaded.
     *
     * @param info the request to be added
     */
    private void addRequestToQueue(RequestInfo info) {
        // Ignore the request if it is already on the queue.
        RequestInfo current = requestQueue;
        RequestInfo prev = null;
        while (current != null) {
            if (current.channel == info.channel && current.date.equals(info.date)) {
                // Upgrade the existing request to a primary day request if necessary.
                if (info.primaryDate.equals(info.date))
                    current.primaryDate = current.date;
                return;
            }
            prev = current;
            current = current.next;
        }
        if (currentRequestChannel == info.channel &&
                currentRequestDate.equals(info.date)) {
            if (info.primaryDate.equals(info.date))
                currentRequestPrimaryDate = currentRequestDate;
            return;
        }

        // Add the request to the end of the queue.
        info.next = null;
        if (prev != null)
            prev.next = info;
        else
            requestQueue = info;

        // If we don't have a request currently in progress, then start it.
        if (currentRequestChannel == null)
            startNextRequest();
    }

    /**
     * Start downloading the next request on the queue.
     */
    private void startNextRequest() {
        for (;;) {
            RequestInfo info = requestQueue;
            if (info == null) {
                currentRequestChannel = null;
                currentRequestDate = null;
                currentRequestPrimaryDate = null;
                if (requestsActive) {
                    requestsActive = false;
                    for (TvNetworkListener listener: listeners)
                        listener.endNetworkRequests();
                }
                break;
            }
            requestQueue = info.next;
            info.next = null;
            if (hasChannelData(info.channel, info.date)) {
                // A previous request on the queue already fetched this data.
                for (TvNetworkListener listener: listeners)
                    listener.dataAvailable(info.channel, info.date, info.primaryDate);
                continue;
            }
            currentRequestChannel = info.channel;
            currentRequestDate = info.date;
            currentRequestPrimaryDate = info.primaryDate;
            if (debug)
                System.out.println("fetching " + info.uri.toString());
            requestsActive = true;
            for (TvNetworkListener listener: listeners)
                listener.setCurrentNetworkRequest(info.channel, info.date, info.primaryDate);
            new DownloadAsyncTask().execute(info);
            break;
        }
    }

    private void reportRequestResult(RequestInfo info) {
        for (TvNetworkListener listener: listeners) {
            if (info.success)
                listener.dataAvailable(info.channel, info.date, currentRequestPrimaryDate);
            else
                listener.requestFailed(info.channel, info.date, currentRequestPrimaryDate);
        }
    }

    public void addNetworkListener(TvNetworkListener listener) {
        listeners.add(listener);
    }
    
    public void removeNetworkListener(TvNetworkListener listener) {
        listeners.remove(listener);
    }
}