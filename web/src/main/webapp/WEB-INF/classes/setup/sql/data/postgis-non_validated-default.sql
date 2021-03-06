--
-- PostgreSQL database dump
--

SET statement_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SET check_function_bodies = false;
SET client_min_messages = warning;

SET search_path = public, pg_catalog;

SET default_tablespace = '';

SET default_with_oids = false;

--
-- Name: non_validated_the_geom_gist; Type: INDEX; Schema: public; Owner: www-data; Tablespace: 
--

CREATE INDEX non_validated_the_geom_gist ON non_validated USING gist (the_geom);


--
-- Name: non_validated; Type: ACL; Schema: public; Owner: www-data
--

REVOKE ALL ON TABLE non_validated FROM PUBLIC;
REVOKE ALL ON TABLE non_validated FROM "www-data";
GRANT ALL ON TABLE non_validated TO "www-data";


--
-- Name: non_validated_gid_seq; Type: ACL; Schema: public; Owner: www-data
--

REVOKE ALL ON SEQUENCE non_validated_gid_seq FROM PUBLIC;
REVOKE ALL ON SEQUENCE non_validated_gid_seq FROM "www-data";
GRANT ALL ON SEQUENCE non_validated_gid_seq TO "www-data";


--
-- PostgreSQL database dump complete
--

