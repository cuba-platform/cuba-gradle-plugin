/*
 * Copyright (c) 2008-2016 Haulmont.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

import com.jelastic.api.Response
import com.jelastic.api.environment.Control
import com.jelastic.api.environment.response.EnvironmentInfoResponse
import com.jelastic.api.environment.response.NodeSSHResponses
import com.jelastic.api.users.Authentication
import com.jelastic.api.users.response.AuthenticationResponse
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.apache.http.HttpEntity
import org.apache.http.HttpHeaders
import org.apache.http.NameValuePair
import org.apache.http.client.HttpClient
import org.apache.http.client.ResponseHandler
import org.apache.http.client.config.CookieSpecs
import org.apache.http.client.config.RequestConfig
import org.apache.http.client.entity.UrlEncodedFormEntity
import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.ContentType
import org.apache.http.entity.HttpEntityWrapper
import org.apache.http.entity.mime.HttpMultipartMode
import org.apache.http.entity.mime.MultipartEntityBuilder
import org.apache.http.entity.mime.content.FileBody
import org.apache.http.entity.mime.content.StringBody
import org.apache.http.impl.client.BasicResponseHandler
import org.apache.http.impl.client.HttpClientBuilder
import org.apache.http.impl.client.HttpClients
import org.apache.http.message.BasicNameValuePair
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.tasks.TaskAction

class CubaJelasticDeploy extends DefaultTask {
    protected static final String SCHEME = 'https'
    protected static final String VERSION = '/1.0/'
    protected static final String UPLOAD_PATH = 'storage/uploader/rest/upload'
    protected static final String ADD_FILE_PATH = 'development/scripting/rest/eval'
    protected static final String GET_ARCHIVES_PATH = "/GetArchives"
    protected static final String DELETE_ARCHIVE_PATH = "/DeleteArchive"
    protected static final String BASE_USER_AGENT = "Mozilla/5.0"
    protected static final int SAME_FILES_LIMIT = 5

    protected String appName
    String email
    String password
    String context = 'ROOT'
    String environment
    String hostUrl

    protected Authentication authenticationService
    protected Control environmentService
    protected File tmpFile
    protected String session
    protected Map<String, String> headers

    protected String warDir

    CubaJelasticDeploy() {
        setDescription('Deploys applications to defined Jelastic server')
        setGroup('Jelastic')
    }

    @TaskAction
    def deployJelastic() {
        project.logger.lifecycle("[CubaJelasticDeploy] Deploying to Jelastic. This may take several minutes to complete")
        init()

        def response = (Map) upload()
        if (response.result == 0) {
            project.logger.info("[CubaJelasticDeploy] uploading: SUCCESS")
            project.logger.info("[CubaJelasticDeploy] file url: ${response.file}")
            addFile(response)
            checkAndStartEnvironment()
            deploy(response.file)
        } else {
            throw new RuntimeException("[CubaJelasticDeploy] upload failed: ${response.error}")
        }

        logout()
    }

    protected void init() {
        for (Object obj : dependsOn) {
            if (obj instanceof CubaWarBuilding) {
                CubaWarBuilding task = obj as CubaWarBuilding
                appName = task.appName
                warDir = task.distrDir
            }
        }
        if (!appName) {
            project.logger.info("[CubaJelasticDeploy] 'appName' is not set, trying to find it automatically")
            for (Map.Entry<String, Project> entry : project.getChildProjects().entrySet()) {
                if (entry.getKey().endsWith("-web")) {
                    def webProject = entry.getValue()

                    CubaDeployment deployWeb = webProject.tasks.getByPath(CubaPlugin.DEPLOY_TASK_NAME) as CubaDeployment
                    appName = deployWeb.appName
                    project.logger.info("[CubaJelasticDeploy] 'appName' is set to '${appName}'")
                    break
                }
            }
        }

        URI uri = new URI(SCHEME, hostUrl, VERSION, null)

        authenticationService = new Authentication()
        authenticationService.setServerUrl(uri.toString())

        environmentService = new Control()
        environmentService.setServerUrl(uri.toString())

        tmpFile = new File("${warDir}/${appName}.war")
        if (!tmpFile.exists()) {
            throw new RuntimeException("[CubaJelasticDeploy] war file not found")
        }

        headers = new HashMap<>(1)
        String userAgent = "${BASE_USER_AGENT} " +
                "CUBA.platform/${project.ext.cubaVersion} " +
                "${project.name}/${project.version}"
        headers.put(HttpHeaders.USER_AGENT, userAgent)

        project.logger.info("[CubaJelasticDeploy] authenticate user...")
        AuthenticationResponse authenticationResponse = authenticationService.signin(email, password, headers)
        if (authenticationResponse.isOK()) {
            project.logger.info("[CubaJelasticDeploy] authentication: SUCCESS")
        } else {
            throw new RuntimeException("[CubaJelasticDeploy] authentication failed: ${authenticationResponse.getError()}")
        }

        session = authenticationResponse.getSession()
    }

    protected Object upload() {
        project.logger.lifecycle("[CubaJelasticDeploy] uploading application...")

        URI uri = new URI(SCHEME, hostUrl, "${VERSION}${UPLOAD_PATH}", null)
        HttpPost httpPost = new HttpPost(uri)

        for (Map.Entry<String, String> entry : headers.entrySet()) {
            httpPost.addHeader(entry.getKey(), entry.getValue())
        }

        final HttpEntity entity = MultipartEntityBuilder.create()
                .setMode(HttpMultipartMode.BROWSER_COMPATIBLE)
                .addPart("fid", new StringBody(appName, ContentType.TEXT_PLAIN))
                .addPart("session", new StringBody(session, ContentType.TEXT_PLAIN))
                .addPart("file", new FileBody(tmpFile))
                .build()

        httpPost.setEntity(new ProgressHttpEntityWrapper(entity, new ProgressHttpEntityWrapper.ProgressCallback() {
            int progress = 0

            @Override
            void progress(float progress) {
                int currProgress = progress
                if (currProgress != this.progress) {
                    if (currProgress % 10 == 0) {
                        project.logger.lifecycle("[CubaJelasticDeploy] uploading progress: [${currProgress}%]")
                    } else {
                        project.logger.info("[CubaJelasticDeploy] uploading progress: [${currProgress}%]")
                    }
                    this.progress = currProgress
                }
            }
        }))

        ResponseHandler<String> responseHandler = new BasicResponseHandler()

        HttpClient httpclient = createHttpClient()
        String response = httpclient.execute(httpPost, responseHandler)
        return new JsonSlurper().parseText(response)
    }

    protected void addFile(Map map) {
        project.logger.info("[CubaJelasticDeploy] adding file to deployment manager...")
        Map<String, String> params = new HashMap<>()

        params.put("script", "UploadArchiveCallback")
        params.put("session", session)

        String comment = 'Uploaded by CUBA Gradle Plugin'
        def data = JsonOutput.toJson([
                name   : map.name,
                archive: map.file,
                link   : 0,
                size   : map.size,
                comment: comment
        ])
        params.put("data", data)

        final HttpClient httpclient = createHttpClient()
        List<NameValuePair> nameValuePairList = new ArrayList<>()

        for (String key : params.keySet()) {
            nameValuePairList.add(new BasicNameValuePair(key, params.get(key)))
        }

        UrlEncodedFormEntity entity = new UrlEncodedFormEntity(nameValuePairList, "UTF-8")
        URI uri = new URI(SCHEME, hostUrl, "${VERSION}${ADD_FILE_PATH}", null)

        HttpPost httpPost = new HttpPost(uri)
        httpPost.setEntity(entity)

        for (Map.Entry<String, String> entry : headers.entrySet()) {
            httpPost.addHeader(entry.getKey(), entry.getValue())
        }

        ResponseHandler<String> responseHandler = new BasicResponseHandler()

        String responseBody = httpclient.execute(httpPost, responseHandler)
        Map response = (Map) new JsonSlurper().parseText(responseBody)
        if (response.result == 0) {
            project.logger.info("[CubaJelasticDeploy] file added: SUCCESS")
        } else {
            project.logger.info("[CubaJelasticDeploy] file added: FAIL")
        }

        removeOldArchives(map.name, comment)
    }

    protected void removeOldArchives(String archiveName, String comment) {
        project.logger.info("[CubaJelasticDeploy] removing old archives...")
        Map<String, String> archivesParams = new HashMap<>()
        archivesParams.put("session", session)

        Map archives = makeRequest(GET_ARCHIVES_PATH, archivesParams)

        if (archives == null
                || archives.result != 0
                || archives.response.result != 0
                || archives.response.objects.isEmpty()) {
            project.logger.info("[CubaJelasticDeploy] can't remove old archives")
            return
        }

        List<Integer> ids = new ArrayList<>()

        for (Map archive : archives.response.objects) {
            if (archive.name.equals(archiveName) && comment.equals(archive.comment)) {
                ids.add(archive.id)
            }
        }

        if (ids.size() <= SAME_FILES_LIMIT) {
            return
        }

        Collections.sort(ids)

        for (int id : ids.subList(0, ids.size() - SAME_FILES_LIMIT)) {
            Map<String, String> parameters = new HashMap<>(archivesParams)
            parameters.put("id", String.valueOf(id))

            makeRequest(DELETE_ARCHIVE_PATH, parameters)
        }
    }

    protected Map makeRequest(String path, Map<String, String> params) {
        final HttpClient httpClient = createHttpClient()
        List<NameValuePair> nameValuePairList = new ArrayList<>()

        for (String key : params.keySet()) {
            nameValuePairList.add(new BasicNameValuePair(key, params.get(key)))
        }

        UrlEncodedFormEntity entity = new UrlEncodedFormEntity(nameValuePairList, "UTF-8")
        URI uri = new URI(SCHEME, hostUrl, path, null)

        HttpPost httpPost = new HttpPost(uri)
        httpPost.setEntity(entity)

        for (Map.Entry<String, String> entry : headers.entrySet()) {
            httpPost.addHeader(entry.getKey(), entry.getValue())
        }

        ResponseHandler<String> responseHandler = new BasicResponseHandler()

        String responseBody = httpClient.execute(httpPost, responseHandler)
        return (Map) new JsonSlurper().parseText(responseBody)
    }

    protected HttpClient createHttpClient() {
        RequestConfig requestConfig = RequestConfig.custom().setCookieSpec(CookieSpecs.IGNORE_COOKIES).build()
        HttpClientBuilder clientBuilder = HttpClients.custom().setDefaultRequestConfig(requestConfig)
        return clientBuilder.build()
    }

    protected void checkAndStartEnvironment() {
        project.logger.info("[CubaJelasticDeploy] checking environment...")
        EnvironmentInfoResponse response = environmentService.getEnvInfo(environment, session, headers)
        if (response.isOK()) {
            // Note: there are following statuses of environment:
            // 1 - running, 2 - down, 3 - launching, 4 - sleep, 6 - creating, 7 - cloning, 8 - exists.
            int status = response.getEnv().getStatus()
            switch (status) {
                case 1: project.logger.info("[CubaJelasticDeploy] environment is running")
                    break
                case 2:
                case 4:
                    project.logger.info("[CubaJelasticDeploy] environment isn't running")
                    startEnv()
                    break
                case 3: // environment wakes up after a sleep
                case 6:
                case 7:
                case 8: throw new RuntimeException("[CubaJelasticDeploy] environment aren't ready to start. " +
                        "Environment status: ${status}")
                    break
            }
        } else {
            throw new RuntimeException("[CubaJelasticDeploy] getting environment info failed: ${response.getError()}")
        }
    }

    protected void startEnv() {
        project.logger.info("[CubaJelasticDeploy] starting environment...")
        Response response = environmentService.startEnv(environment, session, headers)
        if (response.isOK()) {
            project.logger.info("[CubaJelasticDeploy] environment started: SUCCESS")
        } else {
            throw new RuntimeException("[CubaJelasticDeploy] failed to start environment '${environment}': ${response.getError()}")
        }
    }

    protected void deploy(String fileUrl) {
        project.logger.lifecycle("[CubaJelasticDeploy] deploying application...")
        NodeSSHResponses response = environmentService.deployApp(
                environment, session, fileUrl, "${appName}.war", context, true, headers)
        if (response.isOK()) {
            project.logger.info("[CubaJelasticDeploy] deployment: SUCCESS")
        } else {
            throw new RuntimeException("[CubaJelasticDeploy] deployment failed: ${response.getError()}")
        }
    }

    protected void logout() {
        project.logger.info("[CubaJelasticDeploy] sign out user...")
        Response response = authenticationService.signout(session, headers)
        project.logger.info("[CubaJelasticDeploy] sign out: ${response.isOK() ? 'SUCCESS' : 'FAIL'}")
    }

    class ProgressHttpEntityWrapper extends HttpEntityWrapper {

        private final ProgressCallback progressCallback

        static interface ProgressCallback {
            void progress(float progress)
        }

        ProgressHttpEntityWrapper(final HttpEntity entity, final ProgressCallback progressCallback) {
            super(entity)
            this.progressCallback = progressCallback
        }

        @Override
        void writeTo(final OutputStream out) throws IOException {
            this.wrappedEntity.writeTo(out instanceof ProgressFilterOutputStream
                    ? out
                    : new ProgressFilterOutputStream(out, this.progressCallback, getContentLength()))
        }

        static class ProgressFilterOutputStream extends FilterOutputStream {

            private final ProgressCallback progressCallback
            private long transferred
            private long totalBytes

            ProgressFilterOutputStream(
                    final OutputStream out, final ProgressCallback progressCallback, final long totalBytes) {
                super(out)
                this.progressCallback = progressCallback
                this.transferred = 0
                this.totalBytes = totalBytes
            }

            @Override
            void write(final byte[] b, final int off, final int len) throws IOException {
                out.write(b, off, len)
                this.transferred += len
                this.progressCallback.progress(getCurrentProgress())
            }

            @Override
            void write(final int b) throws IOException {
                out.write(b)
                this.transferred++
                this.progressCallback.progress(getCurrentProgress())
            }

            private float getCurrentProgress() {
                return ((float) this.transferred / this.totalBytes) * 100
            }
        }
    }
}
