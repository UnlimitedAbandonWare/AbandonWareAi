package com.example.lms.service.overdrive; 
public final class OverdriveGuard { 
  public boolean shouldOverdrive(double sparsity,double authority,double conflict){ 
    return sparsity>0.7 || authority<0.4 || conflict>0.4; 
  } 
}
