package com.eucalyptus.auth.policy.condition;

public class StringNotEqualsIgnoreCase implements ConditionOp {
  
  @Override
  public boolean check( String key, String value ) {
    return !key.equalsIgnoreCase( value );
  }
  
}
