package edu.whu.cs.nlp.msc;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.LineIterator;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;

import edu.whu.cs.nlp.msc.domain.CompressUnit;
import edu.whu.cs.nlp.msc.giga.GrammarScorer;
import edu.whu.cs.nlp.msc.giga.NGramScore;
import edu.whu.cs.nlp.mts.base.biz.SystemConstant;
import edu.whu.cs.nlp.mts.base.domain.Pair;
import edu.whu.cs.nlp.mts.base.domain.Vector;
import edu.whu.cs.nlp.mts.base.domain.Word;
import edu.whu.cs.nlp.mts.base.utils.EhCacheUtil;
import edu.whu.cs.nlp.mts.base.utils.SerializeUtil;

/**
 * 从压缩输出语句集合中选择关键句构建摘要
 *
 * @author ZhenchaoWang 2015-11-12 15:43:50
 *
 */
public class SentenceReRanker implements Callable<Boolean>, SystemConstant {

    private final Logger                  log = Logger.getLogger(this.getClass());

    /** 当前专题的问句 */
    private final String                  question;
    /** 当前专题对应压缩结果所在路径 */
    private final String                  compressSentencesPath;
    /** 词向量获取器 */
    private final EhCacheUtil             ehCacheUtil;
    /** 语言模型打分器 */
    private final GrammarScorer           grammarScorer;
    /** N元语法模型 */
    private final Map<String, NGramScore> ngramModel;

    public SentenceReRanker(String question, String compressSentencesPath, EhCacheUtil ehCacheUtil, String ngramModelPath) throws IOException {
        super();
        this.question = question;
        this.compressSentencesPath = compressSentencesPath;
        this.ehCacheUtil = ehCacheUtil;
        this.grammarScorer = new GrammarScorer();
        this.ngramModel = this.grammarScorer.loadNgramModel(ngramModelPath);
    }

    @Override
    public Boolean call() throws Exception {

        this.log.info(Thread.currentThread().getName() + " is building summary for [" + this.compressSentencesPath + "]");

        /**
         * 加载当前专题下所有的压缩结果语句，按class组织
         *
         */
        Map<String, List<CompressUnit>> clustedCompressUnits = new HashMap<String, List<CompressUnit>>();

        LineIterator iterator = null;
        try{
            iterator = FileUtils.lineIterator(FileUtils.getFile(this.compressSentencesPath), DEFAULT_CHARSET.toString());

            // 存放压缩结果，按类别组织，key为类名
            String key = null;
            while(iterator.hasNext()) {

                String line = iterator.nextLine();
                if(line.startsWith("classes_")) {
                    key = line;
                    clustedCompressUnits.put(key, new ArrayList<CompressUnit>());
                } else {
                    String[] strs = line.split("#");
                    clustedCompressUnits.get(key).add(new CompressUnit(Float.parseFloat(strs[0]), strs[1]));
                }

            }

        } catch(IOException e) {

            this.log.error("Load compressed file[" + this.compressSentencesPath + "] error!", e);
            throw e;

        } finally {
            if(iterator != null) {
                iterator.close();
            }
        }

        /**
         * 结合句子与问句的相似度重新计算句子对应的权值，重排序
         *
         */
        // 计算问句对应的向量
        double[] quentionVec = this.sentenceToVector(this.question);
        if(quentionVec == null) {
            this.log.error("The question vector is not exist!");
            return false;
        }

        Map<String, List<CompressUnit>> rerankedClustedCompressUnits = new HashMap<String, List<CompressUnit>>();

        List<Pair<Float, String>> classWeightAvg = new ArrayList<Pair<Float,String>>();

        for (Entry<String, List<CompressUnit>> entry : clustedCompressUnits.entrySet()) {

            List<CompressUnit> compressUnits = new ArrayList<CompressUnit>();

            float weightSumInClass = 0.0f;
            for (CompressUnit compressUnit : entry.getValue()) {
                // 计算句子的向量
                double[] sentvec = this.sentenceToVector(compressUnit.getSentence());

                // 利用余弦定理计算句子与问句的距离
                double cosVal = cosineDistence(quentionVec, sentvec);

                // 计算当前句子的语言模型得分
                float fluency = this.grammarScorer.calculateFluency(compressUnit.getSentence(), this.ngramModel);

                float newScore = (float) (cosVal * (fluency + 1.0f) / compressUnit.getScore());
                weightSumInClass += newScore;
                compressUnits.add(new CompressUnit(newScore, compressUnit.getSentence()));
            }

            if(CollectionUtils.isNotEmpty(compressUnits)) {
                // 对句子按照得分从大到小进行排序
                Collections.sort(compressUnits);
                rerankedClustedCompressUnits.put(entry.getKey(), compressUnits);

                // 记录每个类别的权重，用句子权重的均值评估
                classWeightAvg.add(new Pair<Float, String>(weightSumInClass / compressUnits.size(), entry.getKey()));
            }

        }

        String dir = this.compressSentencesPath.substring(0, this.compressSentencesPath.indexOf(DIR_SENTENCES_COMPRESSION));
        String filename = this.compressSentencesPath.substring(Math.max(this.compressSentencesPath.lastIndexOf("\\"), this.compressSentencesPath.lastIndexOf("/")));

        // 序列化reranked后的结果
        File objFile = FileUtils.getFile(dir + "/" + DIR_RERANKED_SENTENCES_COMPRESSION + "/obj", filename);
        try{

            SerializeUtil.writeObj(rerankedClustedCompressUnits, objFile);

        } catch(IOException e){

            this.log.error("Serialize file[" + objFile.getAbsolutePath() + "] error!", e);
            throw e;

        }

        // 以文本形式进行保存
        StringBuilder sbRerankedClustedCompressUnits = new StringBuilder();
        for (Entry<String, List<CompressUnit>> entry : rerankedClustedCompressUnits.entrySet()) {

            if(CollectionUtils.isEmpty(entry.getValue())) {
                continue;
            }

            sbRerankedClustedCompressUnits.append(entry.getKey() + LINE_SPLITER);
            for (CompressUnit unit : entry.getValue()) {
                sbRerankedClustedCompressUnits.append(unit + LINE_SPLITER);
            }

        }

        File textFile = FileUtils.getFile(dir + "/" + DIR_RERANKED_SENTENCES_COMPRESSION + "/text", filename);
        try{
            FileUtils.writeStringToFile(textFile, sbRerankedClustedCompressUnits.toString(), DEFAULT_CHARSET);
        } catch(IOException e) {

            this.log.error("Save file[" + textFile.getAbsolutePath() + "] error!", e);
            throw e;

        }

        // 按照权重由大到小对类别进行排序
        Collections.sort(classWeightAvg, new Comparator<Pair<Float, String>>() {

            @Override
            public int compare(Pair<Float, String> firstPair, Pair<Float, String> secondPair) {
                if(firstPair.getLeft() < secondPair.getLeft()) {
                    return 1;
                } else if(firstPair.getLeft() > secondPair.getLeft()) {
                    return -1;
                } else {
                    return 0;
                }
            }

        });

        // 构建摘要
        StringBuilder summary = new StringBuilder();
        int num = 0;
        int count = 0;
        while(count < MAX_SUMMARY_WORDS_COUNT) {

            for (Pair<Float, String> pair : classWeightAvg) {
                List<CompressUnit> compressUnits = rerankedClustedCompressUnits.get(pair.getRight());
                // 按分数从大到小进行排序
                if(num >= compressUnits.size()) {
                    continue;
                }
                CompressUnit compressUnit = compressUnits.get(num);
                String sentence = compressUnit.getSentence();
                sentence = sentence.replaceAll("\\s+'s", "'s");
                sentence = sentence.replaceAll("-lrb-[\\s\\S]*?-rrb-\\s+", "");
                String[] strs = sentence.split("\\s+");
                summary.append(sentence + "\n");
                for (String str : strs) {
                    if(!PUNCT_EN.contains(str)) {
                        count++;
                    }
                }
                if(count >= MAX_SUMMARY_WORDS_COUNT) {
                    break;
                }
            }

            num++;

        }
        this.log.info("Build summary iterator times:" + num + "\t[" + this.compressSentencesPath + "]");
        int indexOfPoint = filename.lastIndexOf(".");
        String summaryFilename = filename.substring(0, indexOfPoint - 1) + ".M.250." + filename.substring(indexOfPoint - 1, indexOfPoint) + ".3";
        try {
            FileUtils.writeStringToFile(FileUtils.getFile(dir + "/" + DIR_SUMMARIES, summaryFilename), summary.toString().trim(), DEFAULT_CHARSET);
        } catch(IOException e) {
            this.log.error("Save summary[" + filename + "] error!", e);
            throw e;
        }

        return true;
    }

    /**
     * 计算两个向量之间的余弦值<br>
     * 如果小于0，则说明计算出错
     *
     * @param vec1
     * @param vec2
     * @return
     */
    public static double cosineDistence(double[] vec1, double[] vec2) {

        double value = -1;

        if (vec1 == null || vec2 == null) {
            return value;
        }

        // 利用向量余弦值来计算事件之间的相似度
        double scalar = 0; // 两个向量的内积
        double module_1 = 0, module_2 = 0; // 向量vec_1和vec_2的模
        for (int i = 0; i < DIMENSION; ++i) {
            scalar += vec1[i] * vec2[i];
            module_1 += vec1[i] * vec1[i];
            module_2 += vec2[i] * vec2[i];
        }

        if (module_1 > 0 && module_2 > 0) {
            value = scalar / (Math.sqrt(module_1) * Math.sqrt(module_2)) + 1;
        }

        return value;

    }

    /**
     * 计算输入句子的向量
     *
     * @param sentence
     * @return
     */
    private double[] sentenceToVector(String sentence) {
        double[] vector = null;
        if (StringUtils.isBlank(sentence)) {
            return vector;
        }
        vector = new double[DIMENSION];
        Arrays.fill(vector, 0.0D);
        String[] strs = sentence.split("\\s+");
        int count = 0;
        for (String str : strs) {
            if (PUNCT_EN.contains(str)) {
                // 跳过标点
                continue;
            }
            if (STOPWORDS.contains(str)) {
                // 跳过停用词
                continue;
            }

            Word word = new Word();
            word.setName(str);
            word.setLemma(str);
            word.setNer("O");
            try {
                Vector vec = this.ehCacheUtil.getMostSimilarVec(word);
                if (vec == null) {
                    // 如果在词向量中找不到当前的词向量，则跳过
                    continue;
                }
                Float[] floatVec = vec.floatVecs();
                for (int i = 0; i < DIMENSION; i++) {
                    vector[i] += floatVec[i];
                }
                count++;
            } catch (Exception e) {
                this.log.error("Get word[" + str + "] vector error!", e);
            }
        }

        if (count > 0) {
            for (int i = 0; i < DIMENSION; i++) {
                vector[i] /= count;
            }
        }

        return vector;
    }

    public static void main(String[] args) throws Exception {
        ExecutorService es = Executors.newSingleThreadExecutor();
        EhCacheUtil ehCacheUtil = new EhCacheUtil("db_cache_vec", "local");
        Future<Boolean> future = es.submit(new SentenceReRanker("Describe the activities of Morris Dees and the Southern Poverty Law Center.", "E:/workspace/test/compressed-results/D0701A.txt", ehCacheUtil, "E:/workspace/test/giga_3gram.lm"));
        future.get();
        es.shutdown();
        EhCacheUtil.close();
    }

}
