CREATE TABLE IF NOT EXISTS SYMBOLS (
  SID  INTEGER PRIMARY KEY NOT NULL,
  NAME VARCHAR(1024)       NOT NULL
)
  ENGINE = InnoDB;


INSERT INTO SYMBOLS (SID, NAME) VALUES (0, '<INVALID>'), (1, 'HTTP'), (2, 'EJB'), (3, 'SQL'), (4, 'ZICO');


CREATE TABLE IF NOT EXISTS HOSTS (
  HOST_ID    INTEGER AUTO_INCREMENT NOT NULL,
  HOST_NAME  VARCHAR(128)           NOT NULL,
  HOST_ADDR  VARCHAR(128),
  HOST_PATH  VARCHAR(128)           NOT NULL,
  HOST_PASS  VARCHAR(128),
  HOST_FLAGS INTEGER                NOT NULL DEFAULT 0,
  MAX_SIZE   BIGINT                 NOT NULL DEFAULT 1073741824,
  HOST_DESC  VARCHAR(255),
  PRIMARY KEY (HOST_ID)
)
  ENGINE = InnoDB;


CREATE TABLE IF NOT EXISTS TRACES (
  HOST_ID   INTEGER        NOT NULL,
  DATA_OFFS BIGINT         NOT NULL,
  TRACE_ID  INTEGER        NOT NULL,
  DATA_LEN  INTEGER        NOT NULL,
  CLOCK     BIGINT         NOT NULL,
  RFLAGS    INTEGER        NOT NULL,
  TFLAGS    INTEGER        NOT NULL,
  STATUS    INTEGER        NOT NULL,
  CLASS_ID  INTEGER        NOT NULL,
  METHOD_ID INTEGER        NOT NULL,
  SIGN_ID   INTEGER        NOT NULL,
  CALLS     BIGINT         NOT NULL,
  ERRORS    BIGINT         NOT NULL,
  RECORDS   BIGINT         NOT NULL,
  EXTIME    BIGINT         NOT NULL,
  ATTRS     VARCHAR(48000) NOT NULL,
  EXINFO    VARCHAR(16000),

  PRIMARY KEY (HOST_ID, DATA_OFFS)
)
  ENGINE = InnoDB;

CREATE INDEX IX_TRACES__HOST_CLOCK ON TRACES (HOST_ID, CLOCK);
CREATE INDEX IX_TRACES__HOST_EXTIME ON TRACES (HOST_ID, EXTIME);
CREATE INDEX IX_TRACES__HOST_CALLS ON TRACES (HOST_ID, CALLS);
CREATE INDEX IX_TRACES__HOST_ERRORS ON TRACES (HOST_ID, ERRORS);
CREATE INDEX IX_TRACES__HOST_RECORDS ON TRACES (HOST_ID, RECORDS);


CREATE TABLE IF NOT EXISTS TEMPLATES (
  TEMPLATE_ID   INTEGER AUTO_INCREMENT NOT NULL,
  TRACE_ID      INTEGER                NOT NULL,
  ORDER_NUM     INTEGER                NOT NULL,
  FLAGS         INTEGER                NOT NULL DEFAULT 0,
  COND_TEMPLATE VARCHAR(255),
  COND_PATTERN  VARCHAR(255),
  TEMPLATE      VARCHAR(255)           NOT NULL,
  PRIMARY KEY (TEMPLATE_ID)
)
  ENGINE = InnoDB;

INSERT INTO TEMPLATES (TRACE_ID, ORDER_NUM, TEMPLATE) VALUES
(1, 99, "${URI} -> ${STATUS} ${REDIRECT: }"),
(2, 99, "${CLASS}.${METHOD}()"),
(3, 99, "[${DB}] ${SQL}"),
(4, 99, "${HOST} [${LENGTH}]");


CREATE TABLE IF NOT EXISTS TRACE_TYPES (
  HOST_ID  INTEGER NOT NULL,
  TRACE_ID INTEGER NOT NULL,
  PRIMARY KEY (HOST_ID, TRACE_ID)
)
  ENGINE = InnoDB;

COMMIT;

