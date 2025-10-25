package com.example.lms.service.fusion.score; 
import java.util.*;
public final class IsotonicCalibrator {
  public double[] fit(double[] x,double[] y){ 
    int n=Math.min(x.length,y.length); 
    double[] v=Arrays.copyOf(y,n); 
    int[] w=new int[n]; Arrays.fill(w,1);
    for(int i=0;i<n-1;i++){ 
      if(v[i]>v[i+1]){ 
        int j=i; 
        double s=v[i]*w[i]+v[i+1]*w[i+1]; 
        int ww=w[i]+w[i+1]; 
        double a=s/ww; 
        v[i]=v[i+1]=a; 
        w[i]=w[i+1]=ww;
        while(j>0 && v[j-1]>v[j]){ 
          s=v[j-1]*w[j-1]+v[j]*w[j]; 
          ww=w[j-1]+w[j]; 
          a=s/ww; 
          v[j-1]=v[j]=a; 
          w[j-1]=w[j]=ww; 
          j--; 
        } 
      } 
    } 
    return v; 
  }
  public double calibrate(double raw,String source){ return 1.0/(1.0+Math.exp(-(raw-0.5)*6.0)); }
}
