package com.shuzijun.leetcode.plugin.listener;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.wm.ToolWindow;
import com.shuzijun.leetcode.plugin.model.CodeTypeEnum;
import com.shuzijun.leetcode.plugin.model.Question;
import com.shuzijun.leetcode.plugin.setting.PersistentConfig;
import com.shuzijun.leetcode.plugin.utils.HttpClientUtils;
import com.shuzijun.leetcode.plugin.utils.MessageUtils;
import com.shuzijun.leetcode.plugin.utils.URLUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @author shuzijun
 */
public class TestMenuRunnable implements Runnable {
    private final static Logger logger = LoggerFactory.getLogger(TestMenuRunnable.class);
    private Question question;

    private ToolWindow toolWindow;

    private ExecutorService cachedThreadPool = Executors.newCachedThreadPool();

    public TestMenuRunnable(Question question, ToolWindow toolWindow) {
        this.question = question;
        this.toolWindow = toolWindow;
    }

    @Override
    public void run() {

        String codeType = PersistentConfig.getInstance().getInitConfig().getCodeType();
        CodeTypeEnum codeTypeEnum = CodeTypeEnum.getCodeTypeEnum(codeType);
        if (codeTypeEnum == null) {
            MessageUtils.showMsg(toolWindow.getContentManager().getComponent(), MessageType.ERROR, "提示", "请先配置代码类型");
            return;
        }
        if (!HttpClientUtils.isLogin()) {
            MessageUtils.showMsg(toolWindow.getContentManager().getComponent(), MessageType.ERROR, "提示", "请先登陆");
            return;
        }
        String filePath = PersistentConfig.getInstance().getTempFilePath() + question.getTitle() + codeTypeEnum.getSuffix();
        File file = new File(filePath);
        if (!file.exists()) {
            MessageUtils.showMsg(toolWindow.getContentManager().getComponent(), MessageType.ERROR, "提示", "提交测试的代码不存在");
            return;
        } else {
            String code = "";
            Long filelength = file.length();
            byte[] filecontent = new byte[filelength.intValue()];
            try {
                FileInputStream in = new FileInputStream(file);
                in.read(filecontent);
                in.close();
                code = new String(filecontent, "UTF-8");

                HttpPost questionCodePost = new HttpPost(URLUtils.getLeetcodeGraphql());
                StringEntity entityCode = new StringEntity("{\"operationName\":\"questionData\",\"variables\":{\"titleSlug\":\"" + question.getTitleSlug() + "\"},\"query\":\"query questionData($titleSlug: String!) {\\n  question(titleSlug: $titleSlug) {\\n    questionId\\n    questionFrontendId\\n    boundTopicId\\n    title\\n    titleSlug\\n    content\\n    translatedTitle\\n    translatedContent\\n    isPaidOnly\\n    difficulty\\n    likes\\n    dislikes\\n    isLiked\\n    similarQuestions\\n    contributors {\\n      username\\n      profileUrl\\n      avatarUrl\\n      __typename\\n    }\\n    langToValidPlayground\\n    topicTags {\\n      name\\n      slug\\n      translatedName\\n      __typename\\n    }\\n    companyTagStats\\n    codeSnippets {\\n      lang\\n      langSlug\\n      code\\n      __typename\\n    }\\n    stats\\n    hints\\n    solution {\\n      id\\n      canSeeDetail\\n      __typename\\n    }\\n    status\\n    sampleTestCase\\n    metaData\\n    judgerAvailable\\n    judgeType\\n    mysqlSchemas\\n    enableRunCode\\n    enableTestMode\\n    envInfo\\n    __typename\\n  }\\n}\\n\"}");
                questionCodePost.setEntity(entityCode);
                questionCodePost.setHeader("Accept", "application/json");
                questionCodePost.setHeader("Content-type", "application/json");
                CloseableHttpResponse responseCode = HttpClientUtils.executePost(questionCodePost);
                if (responseCode != null && responseCode.getStatusLine().getStatusCode() == 200) {
                    String body = EntityUtils.toString(responseCode.getEntity(), "UTF-8");
                    JSONObject jsonObject = JSONObject.parseObject(body).getJSONObject("data").getJSONObject("question");
                    question.setTestCase(jsonObject.getString("sampleTestCase"));
                    JSONArray jsonArray = jsonObject.getJSONArray("codeSnippets");
                    for (int i = 0; i < jsonArray.size(); i++) {
                        JSONObject object = jsonArray.getJSONObject(i);
                        if (codeTypeEnum.getType().equals(object.getString("lang"))) {
                            question.setLangSlug(object.getString("langSlug"));
                            break;
                        }
                    }
                }
                questionCodePost.abort();

                HttpPost post = new HttpPost(URLUtils.getLeetcodeProblems() + question.getTitleSlug() + "/interpret_solution/");
                JSONObject arg = new JSONObject();
                arg.put("question_id", question.getQuestionId());
                arg.put("data_input", question.getTestCase());
                arg.put("lang", question.getLangSlug());
                arg.put("judge_type", "large");
                arg.put("typed_code", code);
                StringEntity entity = new StringEntity(arg.toJSONString());
                post.setEntity(entity);
                post.setHeader("Accept", "application/json");
                post.setHeader("Content-type", "application/json");
                CloseableHttpResponse response = HttpClientUtils.executePost(post);
                if (response != null && response.getStatusLine().getStatusCode() == 200) {

                    String body = EntityUtils.toString(response.getEntity(), "UTF-8");
                    JSONObject returnObj = JSONObject.parseObject(body);
                    cachedThreadPool.execute(new CheckTask(returnObj));
                    MessageUtils.showMsg(toolWindow.getContentManager().getComponent(), MessageType.INFO, "提示", "已提交,请稍等");
                } else {
                    logger.error("提交测试失败" + EntityUtils.toString(response.getEntity(), "UTF-8"));
                    MessageUtils.showMsg(toolWindow.getContentManager().getComponent(), MessageType.ERROR, "提示", "提交测试失败");
                }
                post.abort();
                return;
            } catch (IOException i) {
                logger.error("读取代码错误");
                MessageUtils.showMsg(toolWindow.getContentManager().getComponent(), MessageType.ERROR, "提示", "读取代码错误");
                return;
            }
        }

    }

    private class CheckTask implements Runnable {
        private JSONObject returnObj;

        public CheckTask(JSONObject returnObj) {
            this.returnObj = returnObj;
        }

        @Override
        public void run() {
            String key = returnObj.getString("interpret_expected_id");
            for (int i = 0; i < 50; i++) {
                try {
                    HttpGet httpget = new HttpGet(URLUtils.getLeetcodeSubmissions() + key + "/check/");
                    CloseableHttpResponse response = HttpClientUtils.executeGet(httpget);
                    if (response != null && response.getStatusLine().getStatusCode() == 200) {
                        String body = EntityUtils.toString(response.getEntity(), "UTF-8");
                        JSONObject jsonObject = JSONObject.parseObject(body);
                        if ("SUCCESS".equals(jsonObject.getString("state"))) {
                            if (!key.equals(returnObj.getString("interpret_id"))) {
                                key = returnObj.getString("interpret_id");
                                returnObj.put("expected_code_answer", jsonObject.getJSONArray("code_answer"));
                            } else {
                                if (jsonObject.getBoolean("run_success")) {
                                    StringBuffer sb = new StringBuffer("运行成功:\n");
                                    sb.append("测试用例:").append(returnObj.getString("test_case")).append("\n");
                                    sb.append("测试结果:").append(jsonObject.getJSONArray("code_answer").getString(0)).append("\n");
                                    sb.append("期望结果:").append(returnObj.getJSONArray("expected_code_answer").getString(0)).append("\n");

                                    MessageUtils.showMsg(toolWindow.getContentManager().getComponent(), MessageType.INFO, "提示", sb.toString());

                                } else {
                                    MessageUtils.showMsg(toolWindow.getContentManager().getComponent(), MessageType.ERROR, "提示", "运行失败:" + jsonObject.getString("compile_error"));
                                }
                                return;
                            }
                        }

                    }
                    httpget.abort();
                    Thread.sleep(300L);
                } catch (Exception e) {
                    logger.error("提交出错", e);
                    MessageUtils.showMsg(toolWindow.getContentManager().getComponent(), MessageType.ERROR, "提示", "获取测试结果错误");
                    return;
                }

            }
            logger.error("等待结果超时");
            MessageUtils.showMsg(toolWindow.getContentManager().getComponent(), MessageType.ERROR, "提示", "等待结果超时");
        }
    }
}
