package lsi.ubu.servicios;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import lsi.ubu.excepciones.AlquilerCochesException;
import lsi.ubu.util.PoolDeConexiones;

public class ServicioImpl implements Servicio {
	private static final Logger LOGGER = LoggerFactory.getLogger(ServicioImpl.class);

	private static final int DIAS_DE_ALQUILER = 4;

	public void alquilar(String nifCliente, String matricula, Date fechaIni, Date fechaFin) throws SQLException {
		PoolDeConexiones pool = PoolDeConexiones.getInstance();

		Connection con = null;
		PreparedStatement st = null;
		ResultSet rs = null;

		/*
		 * El calculo de los dias se da hecho
		 */
		long diasDiff = DIAS_DE_ALQUILER;
		if (fechaFin != null) {
			diasDiff = TimeUnit.MILLISECONDS.toDays(fechaFin.getTime() - fechaIni.getTime());

			if (diasDiff < 1) {
				throw new AlquilerCochesException(AlquilerCochesException.SIN_DIAS);
			}
		}

		try {
			con = pool.getConnection();
			con.setAutoCommit(false);

			/* ================================= AYUDA R�PIDA ===========================*/
			/*
			 * Algunas de las columnas utilizan tipo numeric en SQL, lo que se traduce en
			 * BigDecimal para Java.
			 * 
			 * Convertir un entero en BigDecimal: new BigDecimal(diasDiff)
			 * 
			 * Sumar 2 BigDecimals: usar metodo "add" de la clase BigDecimal
			 * 
			 * Multiplicar 2 BigDecimals: usar metodo "multiply" de la clase BigDecimal
			 *
			 * 
			 * Paso de util.Date a sql.Date java.sql.Date sqlFechaIni = new
			 * java.sql.Date(sqlFechaIni.getTime());
			 *
			 *
			 * Recuerda que hay casos donde la fecha fin es nula, por lo que se debe de
			 * calcular sumando los dias de alquiler (ver variable DIAS_DE_ALQUILER) a la
			 * fecha ini.
			 */
			//Comprobamos que el cliente existe
			st = con.prepareStatement("SELECT COUNT(*) FROM Clientes WHERE NIF = ?");
			st.setString(1, nifCliente);
			rs = st.executeQuery();
			rs.next();
			if (rs.getInt(1) == 0) {
			    throw new AlquilerCochesException(AlquilerCochesException.CLIENTE_NO_EXIST);
			}
			
			//Comprobamos que el vehiculo existe
			st = con.prepareStatement("SELECT COUNT(*) FROM Vehiculos WHERE matricula = ?");
			st.setString(1, matricula);
			rs = st.executeQuery();
			rs.next();
			if (rs.getInt(1) == 0) {
			    throw new AlquilerCochesException(AlquilerCochesException.VEHICULO_NO_EXIST);
			}
		
			//Comprobar que el vehiculo está disponible, comprobamos si la fecha de fin es null o es mayor a la fecha actual, 
            //si lo es no está disponible
            st = con.prepareStatement("SELECT fecha_ini, fecha_fin FROM Reservas WHERE matricula = ?");
            st.setString(1 , matricula);
            rs = st.executeQuery();
            if(rs.next()) {
                java.sql.Date fecha_fin = rs.getDate("fecha_fin");
                if ( fecha_fin == null || fecha_fin.toLocalDate().isBefore(LocalDate.now())) {
                    st.close();
                    rs.close();
                    throw new AlquilerCochesException(AlquilerCochesException.VEHICULO_OCUPADO);
                }
            }

            //limpiar los datos de las comprobaciones
            st.clearBatch();
            st.clearParameters();
            st.close();
            rs.close();
            
            //Ejecutar el alquiler
			//Añadir en la tabla de reservas
			st = con.prepareStatement("INSERT INTO Reservas VALUES (seq_reservas.nextVal, ?, ?, ?, ?)");
			
			java.sql.Date fechaIniSql = new java.sql.Date(fechaIni.getTime());
			
			st.setString(1, nifCliente);
			st.setString(2, matricula);
			st.setDate(3, fechaIniSql);
			
		    if (fechaFin != null) {
		        java.sql.Date fechaFinSql = new java.sql.Date(fechaFin.getTime());
		        st.setDate(4, fechaFinSql);  
		    } else {
		        st.setNull(4, java.sql.Types.DATE);  // Si la fecha es null, establecer el valor como NULL en la base de datos
		    }
		    
			int resulReserva = st.executeUpdate();
			
			//Añadir en la tabla de facturas
			//Calcular el importe de la gasolina - para el total de la factura y la segunda linea de la factura
			st = con.prepareStatement("SELECT capacidad_deposito, precio_por_litro, precio_combustible.tipo_combustible FROM modelos JOIN vehiculos"
					+ " ON (modelos.id_modelo = vehiculos.id_modelo) "
					+ "JOIN precio_combustible ON (modelos.tipo_combustible = precio_combustible.tipo_combustible)"
					+ " WHERE vehiculos.matricula = ?");
			st.setString(1, matricula);
			ResultSet rsGasolina = st.executeQuery();
			rsGasolina.next();
			double importeGasolina = rsGasolina.getInt("capacidad_deposito") * rsGasolina.getDouble("precio_por_litro");
			
			//Calcular el importe total de la factura
			st = con.prepareStatement("SELECT precio_cada_dia FROM modelos JOIN vehiculos ON (modelos.id_modelo = vehiculos.id_modelo)"
					+ "WHERE matricula = ?");
			st.setString(1, matricula);
			rs = st.executeQuery();
			rs.next();
			
			double importeAlquiler = rs.getDouble("precio_cada_dia") * diasDiff;
			
			st = con.prepareStatement("INSERT INTO facturas (nroFactura, importe, cliente)"
					+ "VALUES (seq_num_fact.nextVal, ?, ?)");
			st.setDouble(1, importeAlquiler + importeGasolina);
			st.setString(2, nifCliente);
			
			int resulFactura = st.executeUpdate();
			
			//Añadir en la tabla lineas_factura
			//Crear primera linea de factura
			String concepto1 = diasDiff + " dias de alquiler, vehiculo modelo ";
			st = con.prepareStatement("SELECT id_modelo FROM vehiculos WHERE matricula = ?");
			st.setString(1, matricula);
			ResultSet rsMatricula = st.executeQuery();
			rsMatricula.next();
			concepto1 += "" + rsMatricula.getString("id_modelo");
			rsMatricula.close();
			
			st = con.prepareStatement("INSERT INTO lineas_factura (nroFactura, concepto, importe)"
					+ "VALUES (seq_num_fact.currval, ?, ?)");
			
			st.setString(1, concepto1);
			st.setDouble(2, importeAlquiler);
			int resulLineaFactura1 = st.executeUpdate();
			
			//Crear segunda linea de factura
			String concepto2 = "Deposito lleno de " + rsGasolina.getInt("capacidad_deposito") + " litros de " + rsGasolina.getString("tipo_combustible")+ " ";
			
			
			st = con.prepareStatement("INSERT INTO lineas_factura (nroFactura, concepto, importe)"
					+ "VALUES (seq_num_fact.currval, ?, ?)");
			
			st.setString(1, concepto2);
			st.setDouble(2, importeGasolina);
			int resulLineaFactura2 = st.executeUpdate();
			rsGasolina.close();
			
			con.commit();
			
		}catch(AlquilerCochesException e) {
            if (con != null) con.rollback();
            throw e; //Propaga la excepcion	
        }catch (SQLException e) {
        	try {
        		if (con != null)
        			con.rollback(); 
        	} catch (SQLException ex) { 
        		LOGGER.error("Error al hacer rollback", ex); 
            }

		} finally {
			try {
				if (rs != null) rs.close();
		        if (st != null) st.close();
		        if (con != null) con.close();
		    } catch (SQLException e) {
		        LOGGER.debug("Error cerrando recursos: " + e.getMessage());
		    }
		}
	}
}
