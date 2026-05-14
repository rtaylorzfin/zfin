package org.zfin.zirc.service;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LineSubmissionBackgroundUpdate {

    private Boolean singleAllelic;
    private String maternalBackground;
    private String paternalBackground;
    private Boolean backgroundChangeable;
    private String backgroundChangeConcerns;

}
