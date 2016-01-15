package com.airbnb.di.hive.batchreplication.hivecopy;

import com.airbnb.di.hive.common.HiveObjectSpec;
import com.airbnb.di.hive.replication.primitives.TaskEstimate;
import com.google.common.hash.Hashing;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathFilter;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Mapper;

import java.io.IOException;

import static com.airbnb.di.hive.batchreplication.ReplicationUtils.genValue;
import static com.airbnb.di.hive.batchreplication.hivecopy.MetastoreReplicationJob.deseralizeJobResult;

/**
 * Stage 2 Mapper to handle folder hdfs folder copy
 *
 * Input of this job is stage1 output. It contains action of table and partition. We only care about COPY action in
 * this stage. In the mapper, it will enumerate the directories and figure what files needs to be copied. Since
 * each folder can have unbalanced number files, we use shuffle again to load balance file copy actions.
 * In reducer we actually copy the file.
 */
public class Stage2FolderCopyMapper extends Mapper<LongWritable, Text, LongWritable, Text> {
    private static final Log LOG = LogFactory.getLog(Stage2FolderCopyMapper.class);
    private static final PathFilter hiddenFileFilter = new PathFilter() {
        public boolean accept(Path p) {
            String name = p.getName();
            return !name.startsWith("_") && !name.startsWith(".");
        }
    };

    private Configuration conf;

    protected void setup(Context context) throws IOException, InterruptedException {
        this.conf = context.getConfiguration();
    }

    protected void map(LongWritable key, Text value, Context context) throws IOException, InterruptedException {
        Pair<TaskEstimate, HiveObjectSpec> input = deseralizeJobResult(value.toString());
        TaskEstimate estimate = input.getLeft();
        HiveObjectSpec spec = input.getRight();

        switch (estimate.getTaskType()) {
        case COPY_PARTITION:
        case COPY_UNPARTITIONED_TABLE:
            if (estimate.isUpdateData()) {
                UpdateDirectory(context, spec.getDbName(), spec.getTableName(), spec.getPartitionName(),
                        estimate.getSrcPath().get(), estimate.getDestPath().get());
            }
            break;
        default:
            break;

        }
    }

    private static void hdfsCleanFolder(String db, String table, String part, String dst, Configuration conf, boolean recreate)
            throws  IOException{
        Path dstPath = new Path(dst);

        FileSystem fs = dstPath.getFileSystem(conf);
        if(fs.exists(dstPath) && !fs.delete(dstPath, true)) {
            throw new IOException("Failed to delete dstFolder: " + dstPath.toString());
        }

        if(fs.exists(dstPath)) {
            throw new IOException("Validate delete dstFolder failed: " + dstPath.toString());
        }

        if (!recreate) {
            return;
        }

        fs.mkdirs(dstPath);

        if(!fs.exists(dstPath)) {
            throw new IOException("Validate recreate dstFolder failed: " + dstPath.toString());
        }
    }

    private void UpdateDirectory(Context context, String db, String table, String partition, Path src, Path dst)
            throws IOException, InterruptedException {

        LOG.info("UpdateDirectory:" + dst.toString());

        hdfsCleanFolder(db, table, partition, dst.toString(), this.conf, true);

        try {
            FileSystem srcFs = src.getFileSystem(this.conf);
            LOG.info("src file: " + src.toString());

            for (FileStatus status : srcFs.listStatus(src, hiddenFileFilter)) {
                LOG.info("file: " + status.getPath().toString());

                long hashValue = Hashing.murmur3_128().hashLong(
                        (long) (Long.valueOf(status.getLen()).hashCode() *
                                Long.valueOf(status.getModificationTime()).hashCode())).asLong();

                context.write(new LongWritable(hashValue), new Text(
                        genValue(status.getPath().toString(), dst.toString(), String.valueOf(status.getLen()))));
            }
        } catch (IOException e) {
            // Ignore File list generate error because source directory could be removed while we
            // enumerate it.
            LOG.info("Src dir is removed: " + src);
        }
    }
}
