       IDENTIFICATION DIVISION.
       PROGRAM-ID. BNFUPD.
       AUTHOR. INSURANCE-SYSTEMS-TEAM.
       DATE-WRITTEN. 2024-02-15.
       DATE-COMPILED.

       ENVIRONMENT DIVISION.
       CONFIGURATION SECTION.
       SOURCE-COMPUTER. IBM-ISERIES.
       OBJECT-COMPUTER. IBM-ISERIES.

       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT POLICY-MASTER-FILE
               ASSIGN TO DATABASE-PLCYMST
               ORGANIZATION IS INDEXED
               ACCESS MODE IS RANDOM
               RECORD KEY IS PMR-POLICY-NUMBER
               FILE STATUS IS WS-POLICY-FS.

           SELECT BENEFICIARY-FILE
               ASSIGN TO DATABASE-BNFMST
               ORGANIZATION IS INDEXED
               ACCESS MODE IS DYNAMIC
               RECORD KEY IS BNF-KEY
               ALTERNATE RECORD KEY IS BNF-POLICY-NUMBER
                   WITH DUPLICATES
               FILE STATUS IS WS-BNF-FS.

           SELECT UPDATE-REQUEST-FILE
               ASSIGN TO DATABASE-BNFUPDIN
               ORGANIZATION IS SEQUENTIAL
               ACCESS MODE IS SEQUENTIAL
               FILE STATUS IS WS-REQUEST-FS.

           SELECT AUDIT-LOG-FILE
               ASSIGN TO DATABASE-BNFAUDIT
               ORGANIZATION IS SEQUENTIAL
               ACCESS MODE IS SEQUENTIAL
               FILE STATUS IS WS-AUDIT-FS.

       DATA DIVISION.
       FILE SECTION.

       FD POLICY-MASTER-FILE
           LABEL RECORDS ARE STANDARD
           RECORD CONTAINS 200 CHARACTERS.
       COPY PLCYREC.

       FD BENEFICIARY-FILE
           LABEL RECORDS ARE STANDARD
           RECORD CONTAINS 180 CHARACTERS.
       01 BENEFICIARY-RECORD.
          05 BNF-KEY.
             10 BNF-POLICY-NUMBER       PIC X(10).
             10 BNF-SEQUENCE-NUMBER     PIC 9(02).
          05 BNF-BENEFICIARY-TYPE       PIC X(01).
             88 BNF-PRIMARY                 VALUE 'P'.
             88 BNF-CONTINGENT             VALUE 'C'.
          05 BNF-DESIGNEE-TYPE          PIC X(01).
             88 BNF-INDIVIDUAL              VALUE 'I'.
             88 BNF-TRUST                   VALUE 'T'.
             88 BNF-ESTATE                  VALUE 'E'.
             88 BNF-CHARITY                 VALUE 'C'.
          05 BNF-FULL-NAME              PIC X(50).
          05 BNF-DATE-OF-BIRTH          PIC 9(08).
          05 BNF-SSN                    PIC X(09).
          05 BNF-RELATIONSHIP           PIC X(20).
          05 BNF-ALLOCATION-PCT         PIC 9(03)V99 COMP-3.
          05 BNF-PHONE-NUMBER           PIC X(15).
          05 BNF-ADDRESS                PIC X(60).
          05 BNF-EFFECTIVE-DATE         PIC 9(08).
          05 BNF-FILLER                 PIC X(02).

       FD UPDATE-REQUEST-FILE
           LABEL RECORDS ARE STANDARD
           RECORD CONTAINS 200 CHARACTERS.
       01 UPDATE-REQUEST-RECORD.
          05 URQ-ACTION-CODE            PIC X(01).
             88 URQ-ADD                     VALUE 'A'.
             88 URQ-MODIFY                  VALUE 'M'.
             88 URQ-DELETE                  VALUE 'D'.
          05 URQ-POLICY-NUMBER          PIC X(10).
          05 URQ-SEQUENCE-NUMBER        PIC 9(02).
          05 URQ-BENEFICIARY-TYPE       PIC X(01).
          05 URQ-DESIGNEE-TYPE          PIC X(01).
          05 URQ-FULL-NAME              PIC X(50).
          05 URQ-DATE-OF-BIRTH          PIC 9(08).
          05 URQ-SSN                    PIC X(09).
          05 URQ-RELATIONSHIP           PIC X(20).
          05 URQ-ALLOCATION-PCT         PIC 9(03)V99 COMP-3.
          05 URQ-PHONE-NUMBER           PIC X(15).
          05 URQ-ADDRESS                PIC X(60).
          05 URQ-FILLER                 PIC X(18).

       FD AUDIT-LOG-FILE
           LABEL RECORDS ARE STANDARD
           RECORD CONTAINS 200 CHARACTERS.
       01 AUDIT-LOG-RECORD.
          05 AUD-TIMESTAMP              PIC X(26).
          05 AUD-PROGRAM                PIC X(10).
          05 AUD-ACTION                 PIC X(10).
          05 AUD-POLICY-NUMBER          PIC X(10).
          05 AUD-DETAILS                PIC X(100).
          05 AUD-RESULT                 PIC X(04).
          05 AUD-FILLER                 PIC X(40).

       WORKING-STORAGE SECTION.

       01 WS-FILE-STATUS.
          05 WS-POLICY-FS               PIC X(02) VALUE '00'.
          05 WS-BNF-FS                  PIC X(02) VALUE '00'.
          05 WS-REQUEST-FS              PIC X(02) VALUE '00'.
          05 WS-AUDIT-FS                PIC X(02) VALUE '00'.

       01 WS-COUNTERS.
          05 WS-TOTAL-REQUESTS          PIC 9(06) VALUE ZEROS.
          05 WS-SUCCESSFUL-UPDATES      PIC 9(06) VALUE ZEROS.
          05 WS-FAILED-REQUESTS         PIC 9(06) VALUE ZEROS.

       01 WS-WORK-FIELDS.
          05 WS-TOTAL-PRIMARY-PCT       PIC 9(03)V99 COMP-3 VALUE ZEROS.
          05 WS-TOTAL-CONTING-PCT       PIC 9(03)V99 COMP-3 VALUE ZEROS.
          05 WS-EOF-FLAG                PIC X(01) VALUE 'N'.
             88 WS-END-OF-FILE              VALUE 'Y'.

       COPY ERRMSGS.

       PROCEDURE DIVISION.

       0000-MAIN SECTION.
       0000-MAIN-PARA.
           PERFORM 1000-INIT-PARA
           READ UPDATE-REQUEST-FILE
               AT END MOVE 'Y' TO WS-EOF-FLAG
           END-READ
           PERFORM 2000-PROCESS-REQUESTS-LOOP-PARA
               UNTIL WS-END-OF-FILE
           PERFORM 9000-END-PARA
           STOP RUN.

       1000-INIT SECTION.
       1000-INIT-PARA.
           INITIALIZE WS-FILE-STATUS
           INITIALIZE WS-COUNTERS
           INITIALIZE WS-WORK-FIELDS
           MOVE 'BNFUPD'              TO ERR-PROGRAM-NAME
           MOVE FUNCTION CURRENT-DATE TO ERR-TIMESTAMP
           OPEN INPUT  POLICY-MASTER-FILE
           OPEN I-O    BENEFICIARY-FILE
           OPEN INPUT  UPDATE-REQUEST-FILE
           OPEN EXTEND AUDIT-LOG-FILE
           DISPLAY '** BNFUPD - BENEFICIARY UPDATE STARTED **'.

       2000-PROCESS-REQUESTS-LOOP SECTION.
       2000-PROCESS-REQUESTS-LOOP-PARA.
           ADD 1                        TO WS-TOTAL-REQUESTS
           PERFORM 2100-VALIDATE-POLICY-PARA
           IF ERR-SUCCESS
               EVALUATE TRUE
                   WHEN URQ-ADD
                       PERFORM 3000-ADD-BENEFICIARY-PARA
                   WHEN URQ-MODIFY
                       PERFORM 3100-MODIFY-BENEFICIARY-PARA
                   WHEN URQ-DELETE
                       PERFORM 3200-DELETE-BENEFICIARY-PARA
                   WHEN OTHER
                       MOVE ERR-INVALID-CLAIM TO ERR-RETURN-CODE
                       MOVE 'INVALID ACTION CODE IN REQUEST'
                                        TO ERR-MESSAGE-TEXT
               END-EVALUATE
               IF ERR-SUCCESS
                   PERFORM 2200-VALIDATE-ALLOCATIONS-PARA
               END-IF
           END-IF
           PERFORM 9500-WRITE-AUDIT-PARA
           READ UPDATE-REQUEST-FILE
               AT END MOVE 'Y'         TO WS-EOF-FLAG
           END-READ.

       2100-VALIDATE-POLICY SECTION.
       2100-VALIDATE-POLICY-PARA.
           MOVE ERR-SUCCESS             TO ERR-RETURN-CODE
           MOVE URQ-POLICY-NUMBER       TO PMR-POLICY-NUMBER
           READ POLICY-MASTER-FILE
               INVALID KEY
                   MOVE ERR-INVALID-POLICY TO ERR-RETURN-CODE
                   MOVE 'POLICY NOT FOUND FOR BENEFICIARY UPDATE'
                                        TO ERR-MESSAGE-TEXT
               NOT INVALID KEY
                   IF NOT PMR-POLICY-ACTIVE
                       MOVE ERR-POLICY-CANCELLED TO ERR-RETURN-CODE
                       MOVE 'CANNOT UPDATE BENEFICIARY ON INACTIVE POLICY'
                                        TO ERR-MESSAGE-TEXT
                   END-IF
           END-READ.

       2200-VALIDATE-ALLOCATIONS SECTION.
       2200-VALIDATE-ALLOCATIONS-PARA.
           MOVE ZEROS                   TO WS-TOTAL-PRIMARY-PCT
           MOVE ZEROS                   TO WS-TOTAL-CONTING-PCT
           MOVE URQ-POLICY-NUMBER       TO BNF-POLICY-NUMBER
           START BENEFICIARY-FILE KEY = BNF-POLICY-NUMBER
               INVALID KEY CONTINUE
           END-START
           PERFORM UNTIL WS-BNF-FS = '23' OR WS-BNF-FS NOT = '00'
               READ BENEFICIARY-FILE NEXT RECORD
                   AT END MOVE '23'     TO WS-BNF-FS
                   NOT AT END
                       IF BNF-POLICY-NUMBER = URQ-POLICY-NUMBER
                           IF BNF-PRIMARY
                               ADD BNF-ALLOCATION-PCT TO WS-TOTAL-PRIMARY-PCT
                           ELSE IF BNF-CONTINGENT
                               ADD BNF-ALLOCATION-PCT TO WS-TOTAL-CONTING-PCT
                           END-IF
                       ELSE
                           MOVE '23'    TO WS-BNF-FS
                       END-IF
               END-READ
           END-PERFORM
           IF WS-TOTAL-PRIMARY-PCT NOT = 100.00
               MOVE ERR-INVALID-AMOUNT  TO ERR-RETURN-CODE
               MOVE 'PRIMARY BENEFICIARY ALLOCATIONS MUST TOTAL 100%'
                                        TO ERR-MESSAGE-TEXT
           END-IF.

       3000-ADD-BENEFICIARY SECTION.
       3000-ADD-BENEFICIARY-PARA.
           MOVE URQ-POLICY-NUMBER       TO BNF-POLICY-NUMBER
           MOVE URQ-SEQUENCE-NUMBER     TO BNF-SEQUENCE-NUMBER
           MOVE URQ-BENEFICIARY-TYPE    TO BNF-BENEFICIARY-TYPE
           MOVE URQ-DESIGNEE-TYPE       TO BNF-DESIGNEE-TYPE
           MOVE URQ-FULL-NAME           TO BNF-FULL-NAME
           MOVE URQ-DATE-OF-BIRTH       TO BNF-DATE-OF-BIRTH
           MOVE URQ-SSN                 TO BNF-SSN
           MOVE URQ-RELATIONSHIP        TO BNF-RELATIONSHIP
           MOVE URQ-ALLOCATION-PCT      TO BNF-ALLOCATION-PCT
           MOVE URQ-PHONE-NUMBER        TO BNF-PHONE-NUMBER
           MOVE URQ-ADDRESS             TO BNF-ADDRESS
           MOVE FUNCTION CURRENT-DATE(1:8) TO BNF-EFFECTIVE-DATE
           WRITE BENEFICIARY-RECORD
               INVALID KEY
                   MOVE ERR-DUPLICATE-RECORD TO ERR-RETURN-CODE
                   MOVE 'BENEFICIARY SEQUENCE ALREADY EXISTS'
                                        TO ERR-MESSAGE-TEXT
               NOT INVALID KEY
                   ADD 1                TO PMR-BENEFICIARY-COUNT
                   REWRITE POLICY-MASTER-RECORD
                       INVALID KEY CONTINUE
                   END-REWRITE
                   ADD 1                TO WS-SUCCESSFUL-UPDATES
           END-WRITE.

       3100-MODIFY-BENEFICIARY SECTION.
       3100-MODIFY-BENEFICIARY-PARA.
           MOVE URQ-POLICY-NUMBER       TO BNF-POLICY-NUMBER
           MOVE URQ-SEQUENCE-NUMBER     TO BNF-SEQUENCE-NUMBER
           READ BENEFICIARY-FILE
               INVALID KEY
                   MOVE ERR-RECORD-NOT-FOUND TO ERR-RETURN-CODE
                   MOVE 'BENEFICIARY RECORD NOT FOUND FOR MODIFY'
                                        TO ERR-MESSAGE-TEXT
               NOT INVALID KEY
                   MOVE URQ-FULL-NAME   TO BNF-FULL-NAME
                   MOVE URQ-ALLOCATION-PCT TO BNF-ALLOCATION-PCT
                   MOVE URQ-RELATIONSHIP TO BNF-RELATIONSHIP
                   MOVE URQ-PHONE-NUMBER TO BNF-PHONE-NUMBER
                   MOVE URQ-ADDRESS     TO BNF-ADDRESS
                   REWRITE BENEFICIARY-RECORD
                       INVALID KEY
                           MOVE ERR-FILE-WRITE-FAILED TO ERR-RETURN-CODE
                           MOVE 'BENEFICIARY REWRITE FAILED'
                                        TO ERR-MESSAGE-TEXT
                       NOT INVALID KEY
                           ADD 1        TO WS-SUCCESSFUL-UPDATES
                   END-REWRITE
           END-READ.

       3200-DELETE-BENEFICIARY SECTION.
       3200-DELETE-BENEFICIARY-PARA.
           MOVE URQ-POLICY-NUMBER       TO BNF-POLICY-NUMBER
           MOVE URQ-SEQUENCE-NUMBER     TO BNF-SEQUENCE-NUMBER
           READ BENEFICIARY-FILE
               INVALID KEY
                   MOVE ERR-RECORD-NOT-FOUND TO ERR-RETURN-CODE
                   MOVE 'BENEFICIARY RECORD NOT FOUND FOR DELETE'
                                        TO ERR-MESSAGE-TEXT
               NOT INVALID KEY
                   DELETE BENEFICIARY-FILE
                       INVALID KEY
                           MOVE ERR-FILE-WRITE-FAILED TO ERR-RETURN-CODE
                           MOVE 'BENEFICIARY DELETE FAILED'
                                        TO ERR-MESSAGE-TEXT
                       NOT INVALID KEY
                           SUBTRACT 1 FROM PMR-BENEFICIARY-COUNT
                           REWRITE POLICY-MASTER-RECORD
                               INVALID KEY CONTINUE
                           END-REWRITE
                           ADD 1        TO WS-SUCCESSFUL-UPDATES
                   END-DELETE
           END-READ.

       8000-HANDLE-ERROR SECTION.
       8000-HANDLE-ERROR-PARA.
           DISPLAY '** ERROR IN ' ERR-PROGRAM-NAME ' **'
           DISPLAY '   CODE   : ' ERR-RETURN-CODE
           DISPLAY '   MESSAGE: ' ERR-MESSAGE-TEXT
           ADD 1                        TO WS-FAILED-REQUESTS
           IF ERR-SYSTEM-ERROR OR ERR-FILE-OPEN-FAILED
               PERFORM 9000-END-PARA
               STOP RUN
           END-IF.

       9500-WRITE-AUDIT SECTION.
       9500-WRITE-AUDIT-PARA.
           MOVE ERR-TIMESTAMP           TO AUD-TIMESTAMP
           MOVE 'BNFUPD'               TO AUD-PROGRAM
           MOVE URQ-ACTION-CODE         TO AUD-ACTION
           MOVE URQ-POLICY-NUMBER       TO AUD-POLICY-NUMBER
           MOVE ERR-MESSAGE-TEXT        TO AUD-DETAILS
           MOVE ERR-RETURN-CODE         TO AUD-RESULT
           WRITE AUDIT-LOG-RECORD
               INVALID KEY CONTINUE
           END-WRITE.

       9000-END SECTION.
       9000-END-PARA.
           CLOSE POLICY-MASTER-FILE
           CLOSE BENEFICIARY-FILE
           CLOSE UPDATE-REQUEST-FILE
           CLOSE AUDIT-LOG-FILE
           DISPLAY '** BNFUPD - PROCESSING COMPLETE **'
           DISPLAY '   TOTAL REQUESTS : ' WS-TOTAL-REQUESTS
           DISPLAY '   SUCCESSFUL     : ' WS-SUCCESSFUL-UPDATES
           DISPLAY '   FAILED         : ' WS-FAILED-REQUESTS.