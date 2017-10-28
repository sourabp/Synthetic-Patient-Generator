package org.mitre.synthea.export;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.Bucket;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.services.s3.transfer.Upload;

import java.io.File;
import java.util.Iterator;
import java.util.List;

import org.mitre.synthea.helpers.Config;

public class AWSS3Exporter {
    public static void export(String exportPath){
        String bucketName=Config.get("bucket");
        String keyName=Config.get("zippedOutputName");

        AWSCredentials awsCredentials=new ProfileCredentialsProvider().getCredentials();
        AmazonS3Client s3Client=new AmazonS3Client(awsCredentials);
        Iterator<Bucket> buckets=s3Client.listBuckets().iterator();
        Boolean bucketExists=false;
        while(buckets.hasNext()){
            Bucket bucket=buckets.next();
            if(bucket.getName().equals(bucketName)){
                bucketExists=true;
            }
        }
        if(bucketExists==false){
            s3Client.createBucket(bucketName);
        }

        File exportFile=new File(exportPath);
        try{
            s3Client.putObject(bucketName,keyName,exportFile);
        }catch (AmazonServiceException e){
            System.err.println(e.getErrorMessage());
            System.exit(1);
        }

    }

}
