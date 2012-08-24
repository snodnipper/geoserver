/* Copyright (c) 2001 - 2007 TOPP - www.openplans.org. All rights reserved.
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.test.onlineTest.setup;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.apache.commons.lang.StringUtils;
import org.geotools.data.property.PropertyFeatureReader;
import org.geotools.feature.IllegalAttributeException;
import org.geotools.resources.Classes;
import org.opengis.feature.Property;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.feature.type.GeometryDescriptor;
import org.opengis.feature.type.GeometryType;
import org.opengis.feature.type.PropertyDescriptor;
import org.opengis.filter.identity.FeatureId;

import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.io.WKTWriter;

/**
 * Postgis data setup for app-schema-test with online mode.
 * 
 * @author Rini Angreani (CSIRO Earth Science and Resource Engineering)
 */
@SuppressWarnings("deprecation")
public class AppSchemaTestPostgisSetup extends ReferenceDataPostgisSetup {   
    /**
     * Database schema to be used for postgis test database so they are isolated.
     */
    public static final String ONLINE_DB_SCHEMA = "appschematest";
    
    /**
     * Mapping file database parameters
     */
    public static String DB_PARAMS = "<parameters>" //
        + "\n<Parameter>\n" //
        + "<name>dbtype</name>\n" //
        + "<value>postgis</value>" //
        + "\n</Parameter>" //
        + "\n<Parameter>\n" //
        + "<name>host</name>\n" //
        + "<value>${host}</value>" //
        + "\n</Parameter>" //
        + "\n<Parameter>\n" //
        + "<name>port</name>\n" //
        + "<value>${port}</value>" //
        + "\n</Parameter>" //
        + "\n<Parameter>\n" //
        + "<name>database</name>\n" //
        + "<value>${database}</value>" //
        + "\n</Parameter>" //
        + "\n<Parameter>\n" //
        + "<name>user</name>\n" //
        + "<value>${user}</value>" //
        + "\n</Parameter>" //
        + "\n<Parameter>\n" //
        + "<name>passwd</name>\n" //
        + "<value>${passwd}</value>" //
        + "\n</Parameter>" //
        + "\n<Parameter>\n"
        + "<name>Expose primary keys</name>"
        + "<value>true</value>"
        + "\n</Parameter>" //
        // only for postgis because it's just the public schema for oracle
        // because you have to have sys dba rights to create schemas in oracle
        + "\n<Parameter>\n" //
        + "<name>schema</name>\n" //
        + "<value>" //
        + ONLINE_DB_SCHEMA //
        + "</value>" //
        + "\n</Parameter>" //
        + "\n</parameters>"; //

    private String sql;

    /**
     * Ensure the app-schema properties file is loaded with the database parameters. Also create
     * corresponding tables on the database based on data from properties files.
     * 
     * @param propertyFiles
     *            Property file name and its feature type directory map
     * @throws Exception
     */
    public static AppSchemaTestPostgisSetup getInstance(Map<String, File> propertyFiles) throws Exception {
        return new AppSchemaTestPostgisSetup(propertyFiles);
    }
    
    /**
     * Factory method.
     * 
     * @param propertyFiles
     *            Property file name and its parent directory map
     * @return This class instance.
     * @throws Exception
     */
    public AppSchemaTestPostgisSetup(Map<String, File> propertyFiles) throws Exception {
        configureFixture();
        createTables(propertyFiles);
    }

    /**
     * Write SQL string to create tables in the test database based on the property files.
     * 
     * @param propertyFiles
     *            Property files from app-schema-test suite.
     * @throws IllegalAttributeException
     * @throws NoSuchElementException
     * @throws IOException
     */
    private void createTables(Map<String, File> propertyFiles) throws IllegalAttributeException,
            NoSuchElementException, IOException {
        StringBuffer buf = new StringBuffer();
        // drop schema if exists to start clean
        buf.append("DROP SCHEMA IF EXISTS ").append(ONLINE_DB_SCHEMA).append(" CASCADE;\n");
        buf.append("CREATE SCHEMA ").append(ONLINE_DB_SCHEMA).append(";\n");
        for (String fileName : propertyFiles.keySet()) {
            PropertyFeatureReader reader = new PropertyFeatureReader(propertyFiles.get(fileName),
            // remove .properties as it will be added in PropertyFeatureReader constructor
                    fileName.substring(0, fileName.lastIndexOf(".properties")));
            SimpleFeatureType schema = reader.getFeatureType();
            String tableName = schema.getName().getLocalPart();
            // create the table
            buf.append("CREATE TABLE ").append(ONLINE_DB_SCHEMA).append(".\"").append(tableName)
                    .append("\"(");
            List<GeometryDescriptor> geoms = new ArrayList<GeometryDescriptor>();
            // + id +pkey
            int size = schema.getAttributeCount() + 2;
            String[] fieldNames = new String[size];
            List<String> createParams = new ArrayList<String>();
            int j = 0;
            String field;
            String type;
            for (PropertyDescriptor desc : schema.getDescriptors()) {
                if (desc instanceof GeometryDescriptor) {
                    geoms.add((GeometryDescriptor) desc);
                } else {
                    field = "\"" + desc.getName() + "\" ";
                    type = Classes.getShortName(desc.getType().getBinding());
                    if (type.equalsIgnoreCase("String")) {
                        type = "TEXT";
                    } else if (type.equalsIgnoreCase("Double")) {
                        type = "DOUBLE PRECISION";
                    }
                    field += type;
                    createParams.add(field);
                }
                fieldNames[j] = desc.getName().toString();
                j++;
            }
            if (schema.isIdentified()) {
                fieldNames[j] = "ROW_ID";
                createParams.add("\"ROW_ID\" TEXT");
                j++;
            }
            // Add numeric PK for sorting
            fieldNames[j] = "PKEY";
            createParams.add("\"PKEY\" INTEGER");
            buf.append(StringUtils.join(createParams.iterator(), ", "));
            buf.append(");\n");

            // add geometry columns
            for (GeometryDescriptor geom : geoms) {
                buf.append("SELECT AddGeometryColumn ('").append(ONLINE_DB_SCHEMA).append("', ");
                buf.append("'").append(tableName).append("', ");
                buf.append("'").append(geom.getName().toString()).append("', ");
                int srid = getSrid(geom.getType());
                buf.append(srid).append(", ");
                // TODO: should read the properties file header to see if they're more specific
                buf.append("'GEOMETRY'").append(", ");
                // TODO: how to work this out properly?
                buf.append(geom.getType().getCoordinateReferenceSystem().getCoordinateSystem().getDimension());
                buf.append(");\n");
            }

            // then insert rows
            SimpleFeature feature;
            FeatureId id;
            // primary key sequence
            int pKey = 0;
            while (reader.hasNext()) {
                buf.append("INSERT INTO ").append(ONLINE_DB_SCHEMA).append(".\"").append(tableName)
                        .append("\"(\"");
                feature = reader.next();
                buf.append(StringUtils.join(fieldNames, "\", \""));
                buf.append("\") ");
                buf.append("VALUES (");
                Collection<Property> properties = feature.getProperties();
                String[] values = new String[size];
                int valueIndex = 0;
                for (Property prop : properties) {
                    Object value = prop.getValue();
                    if (value instanceof Geometry) {
                    	//use wkt writer to convert geometry to string, so third dimension can be supported if present.
                    	Geometry geom = (Geometry) value;
                    	value = new WKTWriter(geom.getCoordinate().z == Double.NaN? 2 : 3).write(geom);
                    }
                    if (value == null || value.toString().equalsIgnoreCase("null")) {
                        values[valueIndex] = "null";
                    } else if (prop.getType() instanceof GeometryType) {
                        int srid = getSrid(((GeometryType) prop.getType()));
                        if (srid > -1) {
                            // attach srid
                            values[valueIndex] = "ST_GeomFromText('" + value + "', " + srid + ")";
                        } else {
                            values[valueIndex] = "ST_GeomFromText('" + value + "')";
                        }
                    } else {
                        values[valueIndex] = "'" + value + "'";
                    }
                    valueIndex++;
                }

                id = feature.getIdentifier();
                if (id != null) {
                    values[valueIndex] = "'" + id.toString() + "'";
                    valueIndex++;
                }
                // insert primary key
                values[valueIndex] = "'" + pKey + "'";
                pKey++;
                buf.append(StringUtils.join(values, ","));
                buf.append(");\n");
            }
        }
        if (buf.length() > 0) {
            this.sql = buf.toString();
        }
    }

    @Override
    protected void runSqlInsertScript() throws Exception {
        System.out.println(sql);
        this.run(sql, false);
    }
}