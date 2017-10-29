package org.mitre.synthea.export;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.Bucket;

import java.io.File;
import java.util.Iterator;

import com.amazonaws.services.s3.model.CannedAccessControlList;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.transfer.TransferManager;
import org.mitre.synthea.helpers.LocalConfig;

public class AWSS3Exporter {
    public static String export(String exportPath){
        String bucketName= LocalConfig.get("bucket");
        String keyName= LocalConfig.get("zippedOutputName");

        AWSCredentials awsCredentials=new ProfileCredentialsProvider().getCredentials();
        TransferManager transferManager=new TransferManager(awsCredentials);
        AmazonS3Client s3Client=new AmazonS3Client(awsCredentials);
        Iterator<Bucket> buckets=s3Client.listBuckets().iterator();
        Boolean bucketExists=false;
        while(buckets.hasNext()){
            Bucket bucket=buckets.next();
            if(bucket.getName().equals(bucketName)){
                bucketExists=true;
            }
        }
        if(!bucketExists){
            s3Client.createBucket(bucketName);
        }

        File exportFile=new File(exportPath);
        try{
            transferManager.upload(new PutObjectRequest(bucketName,keyName,exportFile).withCannedAcl(CannedAccessControlList.PublicRead));
        }catch (AmazonServiceException e){
            System.err.println(e.getErrorMessage());
            System.exit(1);
        }
        return s3Client.getUrl(bucketName,keyName).toString();
    }
}
