package com.wwr.apipost.handle.apipost.action;

import cn.hutool.core.collection.ListUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.google.gson.JsonObject;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.wwr.apipost.action.AbstractAction;
import com.wwr.apipost.config.ApiPostConfig;
import com.wwr.apipost.config.domain.Api;
import com.wwr.apipost.config.domain.EventData;
import com.wwr.apipost.handle.apipost.config.ApiPostSettings;
import com.wwr.apipost.handle.apipost.config.ApiPostSettingsDialog;
import com.wwr.apipost.handle.apipost.config.ApiPostWorkDirDialog;
import com.wwr.apipost.handle.apipost.domain.ApiPostSyncRequestEntity;
import com.wwr.apipost.handle.apipost.domain.ApiPostSyncResponseVO;
import com.wwr.apipost.openapi.OpenApiDataConvert;
import com.wwr.apipost.openapi.OpenApiGenerator;
import com.wwr.apipost.parse.util.NotificationUtils;
import com.wwr.apipost.util.FileUtilsExt;
import com.wwr.apipost.util.psi.PsiModuleUtils;
import io.swagger.v3.oas.models.OpenAPI;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.wwr.apipost.config.DefaultConstants.API_POST_PROJECT_ID_PREFIX;
import static com.wwr.apipost.parse.util.NotificationUtils.notifyError;
import static com.wwr.apipost.parse.util.NotificationUtils.notifyInfo;
import static com.wwr.apipost.util.JsonUtils.fromJson;
import static com.wwr.apipost.util.JsonUtils.toJson;


/**
 * <p>
 *
 * </p>
 *
 * @author wwr
 * @version 1.0
 * @date 2023/3/24
 *
 * @since 1.0.1
 */
public class ApiPostUploadAction extends AbstractAction {

    public static final String ACTION_TEXT = "Upload To ApiPost";

    @SuppressWarnings("unused all")
    public ApiPostUploadAction() {
        super(IconLoader.getIcon("/icons/upload.png", ApiPostUploadAction.class), true);
    }

    @Override
    public boolean before(AnActionEvent event, ApiPostConfig config) {
        Project project = event.getData(CommonDataKeys.PROJECT);
        ApiPostSettings settings = ApplicationManager.getApplication().getService(ApiPostSettings.class);
        settings.setProjectId(config.getApiPostProjectId());
        if (!settings.isValidate()) {
            ApiPostSettingsDialog dialog = ApiPostSettingsDialog.show(project, event.getPresentation().getText());
            return !dialog.isCanceled();
        }
        return true;
    }

    @Override
    public boolean after(AnActionEvent event, ApiPostConfig config, EventData data) {
        ApiPostSettings settings = ApplicationManager.getApplication().getService(ApiPostSettings.class);
        if (StringUtils.isNotBlank(settings.getProjectId())) {
            config.setApiPostProjectId(settings.getProjectId());
            try {
                File localDefaultFileCache = data.getLocalDefaultFileCache();
                if (localDefaultFileCache == null) {
                    NotificationUtils.notifyError("apipost", "配置写入失败");
                } else {
                    FileUtilsExt.writeText(localDefaultFileCache, API_POST_PROJECT_ID_PREFIX + "=" + settings.getProjectId());
                }
            } catch (IOException e) {
                NotificationUtils.notifyError("apipost", "配置写入失败");
                return false;
            }
        }
        return true;
    }

    @Override
    public void handle(AnActionEvent event, ApiPostConfig config, List<Api> apis) {
        // 获取当前类所在模块名
        Module module = PsiModuleUtils.findModuleByEvent(event);
        ApiPostSettings settings = ApiPostSettings.getInstance();
        String token = settings.getToken();
        String projectId = settings.getProjectId();
        String remoteUrl = settings.getRemoteUrl();
        String workDir = settings.getWorkDir();
        if(null!=workDir&&!"".equals(workDir)&&workDir.contains(",")){
            ApiPostWorkDirDialog.show(event.getData(CommonDataKeys.PROJECT), event.getPresentation().getText(), module, apis);
        }else{
            if(null!=workDir && !"".equals(workDir)){
                for (Api api : apis) {
                    api.setCategory(workDir);
                }
            }
            if (apis.size() < 1){
                notifyInfo("Upload Result","Api not found!");
                return;
            }
            //分片上传
            AtomicInteger success = new AtomicInteger(0);
            AtomicInteger fail = new AtomicInteger(0);
            Set<String> resultSet = new CopyOnWriteArraySet<>();
            List<List<Api>> split = ListUtil.split(apis, 200);
            ExecutorService executorService= Executors.newFixedThreadPool(split.size());
            List<CompletableFuture> completableFutureList = new ArrayList<>();
            for (int i = 0; i < split.size(); i++) {
                List<Api> apiList = split.get(i);
                int size = apiList.size();
                CompletableFuture<Void> uploadResult = CompletableFuture.runAsync(() -> {
                    OpenAPI openApi = new OpenApiDataConvert().convert(apiList);

                    openApi.getInfo().setTitle(module.getName());
                    JsonObject apiJsonObject = new OpenApiGenerator().generate(openApi);
                    // 上传到ApiPost
                    ApiPostSyncRequestEntity entity = new ApiPostSyncRequestEntity();
                    entity.setOpenApi(apiJsonObject);
                    entity.setProjectId(projectId);
                    String requestBodyJson = toJson(entity);
                    try {
                        HttpResponse response = HttpRequest.post(remoteUrl)
                                .header("Content-Type", "application/json")
                                .header("token", token)
                                .body(requestBodyJson)
                                .execute();

                        if (!response.isOk()) {
                            fail.addAndGet(size);
                            resultSet.add("Upload failed! system error");
                            return;
                        }
                        String responseBody = response.body();
                        ApiPostSyncResponseVO responseVO = fromJson(responseBody, ApiPostSyncResponseVO.class);

                        if (responseVO.isSuccess()) {
                            success.addAndGet(size);
                        } else {
                            fail.addAndGet(size);
                            resultSet.add("Upload failed!" + responseVO.getMessage());
                        }
                    } catch (Exception e) {
                        fail.addAndGet(size);
                        resultSet.add("upload error: network error!");
                    }
                },executorService);
                completableFutureList.add(uploadResult);
            }

            try {
                CompletableFuture.allOf(completableFutureList.toArray(new CompletableFuture[0])).get();
            } catch (InterruptedException | ExecutionException e) {
                NotificationUtils.notifyInfo("Upload Result", "Upload fail");
            }
            String result = "";
            if (success.get() != 0) {
                result += String.format("Upload %d Api success!", success.get());
            }
            if (fail.get() != 0) {
                result += String.format("Upload %s Api fail! %s", fail.get(), resultSet);
            }
            NotificationUtils.notifyInfo("Upload Result", result);
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setText(ACTION_TEXT);
    }

    @Override
    public void setDefaultIcon(boolean isDefaultIconSet) {
        super.setDefaultIcon(isDefaultIconSet);
    }
}
